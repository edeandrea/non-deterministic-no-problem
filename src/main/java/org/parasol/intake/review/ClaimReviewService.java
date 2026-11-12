package org.parasol.intake.review;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import jakarta.transaction.Transactional.TxType;
import jakarta.transaction.Transactional;

import org.parasol.claim.model.Claim;
import org.parasol.intake.config.IntakeConfig;
import org.parasol.intake.model.ClaimIntakeState;
import org.parasol.intake.workflow.ClaimIntakeFlow;

import io.quarkus.logging.Log;
import io.serverlessworkflow.impl.WorkflowStatus;

/**
 * A reviewer's decision about a {@code Pending Review} claim. Claims the review under the claim's optimistic lock (the
 * {@code ClaimIntakeState} version), confirms the run is waiting in this instance, then publishes the decision for the intake
 * workflow to apply.
 * <p>
 *   This is asynchronous: {@link #submit} returns once the run has taken the decision, not once the workflow has applied it
 *   (that can still involve a mail send). Claiming the review is what a losing reply ({@code SupersedeStep}) and a losing
 *   decision race on; whichever commits first wins.
 * </p>
 * <p>
 *   A run's {@code WAITING} status becomes visible a couple of statements before its {@code listen} task registers a consumer, so
 *   a decision published right then can be missed. The decision is therefore published again, every
 *   {@code parasol.intake.review.publish-interval}, until the run has taken it (the claim's {@code decidingRunId} no longer names
 *   the run: the decision steps clear it when they apply the decision, and when they fault and put the claim back in review) or
 *   {@code parasol.intake.review.max-publish-attempts} is reached. Republishing a decision the run already consumed is harmless:
 *   the run id and the claim's state are checked again when it's delivered. A decision nobody took puts the review back to open,
 *   so the reviewer can decide again.
 * </p>
 */
@ApplicationScoped
public class ClaimReviewService {
	private final ClaimIntakeFlow flow;
	private final ReviewDecisionPublisher publisher;
	private final IntakeConfig config;

	ClaimReviewService(ClaimIntakeFlow flow, ReviewDecisionPublisher publisher, IntakeConfig config) {
		this.flow = flow;
		this.publisher = publisher;
		this.config = config;
	}

	/**
	 * Submits a reviewer's decision. Nothing is published unless every check passes, in this order: the claim exists, it's
	 * {@code Pending Review} with its review open (no other decision in flight), it's still at the reviewer's version, the
	 * reviewed values fit the claim, and its run is waiting for the decision in this instance.
	 *
	 * @param claimId the claim being reviewed
	 * @param expectedVersion the {@code ClaimIntakeState} version the reviewer saw
	 * @param decision what the reviewer decided
	 * @throws org.parasol.claim.model.ClaimNotFoundException if the claim doesn't exist
	 * @throws ReviewNotOpenException if the claim has no review open to decide
	 * @throws ReviewLockLostException if the claim changed since the reviewer saw it
	 * @throws ReviewedFieldsRejectedException if a reviewed value doesn't fit the claim
	 * @throws ReviewRunNotWaitingException if the claim's run isn't waiting for a decision here, or never took it
	 */
	public void submit(long claimId, long expectedVersion, ReviewDecision decision) {
		var decided = claimReview(claimId, expectedVersion, decision);

		publishUntilTaken(decided);
	}

	/**
	 * Whether a decision would actually succeed right now. {@link ClaimIntakeState#isOpenForReview()} alone isn't
	 * enough: {@code reviewRunId} is set in the workflow's {@code applyOutcome} step, several steps before the run
	 * reaches {@code waitReview} (it still has to send the reply and file the email first), and the run records that it
	 * has in {@link ClaimIntakeState#reviewWaitingRunId}. The run also has to exist in this instance: after a restart
	 * the claim names a run nobody hosts until it's reclaimed.
	 *
	 * @param state the claim's intake state
	 * @return {@code true} only once the run is genuinely waiting for a decision
	 */
	public boolean isReadyForDecision(ClaimIntakeState state) {
		return state.isReadyForDecision() && isWaitingHere(state.reviewRunId);
	}

	// Its own transaction: losing the claim's lock to a reply or another decision fails it at the flush, which is
	// turned into a ReviewLockLostException here. The reviewed values are only checked against the claim here; the
	// decision step sets them, in the transaction that changes the status. A run that isn't waiting fails it too, so
	// the claim isn't left "being decided"
	// Non-private and non-final, so the transaction interceptor applies to the call from this class
	@Transactional(TxType.REQUIRES_NEW)
	ReviewDecided claimReview(long claimId, long expectedVersion, ReviewDecision decision) {
		var state = ReviewGuard.openReview(claimId, expectedVersion, LockModeType.NONE);

		ensureFits(state.claim, decision.fields());

		if (!isReadyForDecision(state)) {
			throw new ReviewRunNotWaitingException(claimId, state.reviewRunId);
		}

		try {
			state.decidingRunId = state.reviewRunId;
			state.reviewRunId = null;
			ClaimIntakeState.flush();
		}
		catch (RuntimeException e) {
			throw isOptimisticLockFailure(e) ? new ReviewLockLostException(claimId) : e;
		}

		return new ReviewDecided(claimId, state.decidingRunId, decision);
	}

	private void publishUntilTaken(ReviewDecided decided) {
		var settings = this.config.review();

		IntStream.range(0, settings.maxPublishAttempts())
			.takeWhile(_ -> isBeingDecided(decided))
			.forEach(_ -> publishAndWait(decided, settings.publishInterval()));

		if (isBeingDecided(decided)) {
			restoreReview(decided.claimId(), decided.runId());
			throw new ReviewRunNotWaitingException(decided.claimId(), decided.runId());
		}
	}

	private void publishAndWait(ReviewDecided decided, Duration interval) {
		publish(decided);
		LockSupport.parkNanos(interval.toNanos());
	}

	private void publish(ReviewDecided decided) {
		try {
			this.publisher.publish(decided);
		}
		catch (RuntimeException e) {
			Log.warnf(e, "Couldn't publish the review decision for claim %d to run %s", decided.claimId(), decided.runId());
		}
	}

	// A PENDING instance is a stand-in Flow leaves behind for a run it doesn't host; only a live waiting one is a run here
	@SuppressWarnings("resource")
	private boolean isWaitingHere(String runId) {
		return this.flow.definition().activeInstance(runId)
			.filter(run -> run.status() == WorkflowStatus.WAITING)
			.isPresent();
	}

	// Non-private and non-final, so the transaction interceptor applies to the call from this class
	@Transactional(TxType.REQUIRES_NEW)
	boolean isBeingDecided(ReviewDecided decided) {
		return ClaimIntakeState.findForClaim(decided.claimId())
			.filter(state -> decided.runId().equals(state.decidingRunId))
			.isPresent();
	}

	// Compensating transaction: a decision nobody took would otherwise leave the claim stuck "being decided". Only while the
	// decision is still the claim's, so one the run took after all isn't undone
	// Non-private and non-final, so the transaction interceptor applies to the call from this class
	@Transactional(TxType.REQUIRES_NEW)
	void restoreReview(long claimId, String runId) {
		ClaimIntakeState.findForClaim(claimId)
			.filter(state -> runId.equals(state.decidingRunId))
			.ifPresent(state -> {
				state.reviewRunId = runId;
				state.decidingRunId = null;
			});
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
