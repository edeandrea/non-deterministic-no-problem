package org.parasol.intake.review;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import jakarta.transaction.Transactional.TxType;
import jakarta.transaction.Transactional;

import org.parasol.claim.model.Claim;
import org.parasol.intake.model.ClaimIntakeState;
import org.parasol.intake.workflow.signal.RunSignalRelay;

/**
 * A reviewer's decision about a {@code Pending Review} claim. Claims the review under the claim's optimistic lock (the
 * {@code ClaimIntakeState} version), confirms the run has reached its wait, and writes the decision as a signal for that run, all in
 * one transaction. Then it asks {@link RunSignalRelay} to deliver it.
 * <p>
 *   This is asynchronous: {@link #submit} returns once the decision is durably queued, not once the workflow has applied it (that can
 *   still involve a mail send). Claiming the review is what a losing reply ({@code SupersedeStep}) and a losing decision race on;
 *   whichever commits first wins.
 * </p>
 * <p>
 *   The run may not be in this JVM, and may not have registered its {@code listen} consumer yet (a run's {@code WAITING} status becomes
 *   visible a couple of statements before it does). Neither matters here: the signal stays until the run has taken it, and
 *   the relay of the application instance that hosts the run republishes it until then.
 * </p>
 */
@ApplicationScoped
public class ClaimReviewService {
	private final RunSignalRelay relay;

	ClaimReviewService(RunSignalRelay relay) {
		this.relay = relay;
	}

	/**
	 * Submits a reviewer's decision. Nothing is queued unless every check passes, in this order: the claim exists, it's
	 * {@code Pending Review} with its review open (no other decision in flight), it's still at the reviewer's version, the
	 * reviewed values fit the claim, and its run is waiting for the decision.
	 *
	 * @param claimId the claim being reviewed
	 * @param expectedVersion the {@code ClaimIntakeState} version the reviewer saw
	 * @param decision what the reviewer decided
	 * @throws org.parasol.claim.model.ClaimNotFoundException if the claim doesn't exist
	 * @throws ReviewNotOpenException if the claim has no review open to decide
	 * @throws ReviewLockLostException if the claim changed since the reviewer saw it
	 * @throws ReviewedFieldsRejectedException if a reviewed value doesn't fit the claim
	 * @throws ReviewRunNotWaitingException if the claim's run isn't waiting for a decision
	 */
	public void submit(long claimId, long expectedVersion, ReviewDecision decision) {
		claimReview(claimId, expectedVersion, decision);
		this.relay.deliver();
	}

	/**
	 * Whether a decision would actually succeed right now. {@link ClaimIntakeState#isOpenForReview()} alone isn't
	 * enough: {@code reviewRunId} is set in the workflow's {@code applyOutcome} step, several steps before the run
	 * reaches {@code waitReview} (it still has to send the reply and file the email first), and the run records that it
	 * has in {@link ClaimIntakeState#reviewWaitingRunId}. A claim can read as open for review while its run is still
	 * doing that, in which case a decision submitted right then would be rejected with {@link ReviewRunNotWaitingException}.
	 *
	 * @param state the claim's intake state
	 * @return {@code true} only once the run is genuinely waiting for a decision
	 */
	public boolean isReadyForDecision(ClaimIntakeState state) {
		return state.isReadyForDecision();
	}

	// Its own transaction: losing the claim's lock to a reply or another decision fails it at the flush, which is
	// turned into a ReviewLockLostException here. The reviewed values are only checked against the claim here; the
	// decision step sets them, in the transaction that changes the status. A run that isn't waiting fails it too, so
	// the claim isn't left "being decided", and the signal is written with the claim's change or not at all
	// Non-private and non-final, so the transaction interceptor applies to the call from this class
	@Transactional(TxType.REQUIRES_NEW)
	void claimReview(long claimId, long expectedVersion, ReviewDecision decision) {
		var state = ReviewGuard.openReview(claimId, expectedVersion, LockModeType.NONE);

		ensureFits(state.claim, decision.fields());

		if (!state.isReadyForDecision()) {
			throw new ReviewRunNotWaitingException(claimId, state.reviewRunId);
		}

		try {
			state.decidingRunId = state.reviewRunId;
			state.reviewRunId = null;
			this.relay.enqueue(state.claim, new ReviewDecided(claimId, state.decidingRunId, decision));
			ClaimIntakeState.flush();
		}
		catch (RuntimeException e) {
			throw isOptimisticLockFailure(e) ? new ReviewLockLostException(claimId) : e;
		}
	}

	// The policy has to be in force on the incident date. Only the intake sets an inception date (generated before the
	// extracted incident date), so a claim without one has nothing to check
	private static void ensureFits(Claim claim, ReviewedFields fields) {
		if (incidentPrecedesPolicy(claim, fields)) {
			throw new ReviewedFieldsRejectedException(claim.id,
				"the incident date %s is before the policy's inception date %s".formatted(fields.incidentDate(), claim.inceptionDate));
		}
	}

	private static boolean incidentPrecedesPolicy(Claim claim, ReviewedFields fields) {
		return Optional.ofNullable(fields.incidentDate())
			.filter(incidentDate -> (claim.inceptionDate != null) && incidentDate.isBefore(claim.inceptionDate))
			.isPresent();
	}

	private static boolean isOptimisticLockFailure(RuntimeException e) {
		return Stream.iterate(e, Objects::nonNull, Throwable::getCause).anyMatch(OptimisticLockException.class::isInstance);
	}
}
