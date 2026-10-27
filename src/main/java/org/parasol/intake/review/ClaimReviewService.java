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
import org.parasol.intake.workflow.ClaimIntakeFlow;

import io.serverlessworkflow.impl.WorkflowInstance;
import io.serverlessworkflow.impl.WorkflowStatus;
import io.serverlessworkflow.impl.persistence.PersistenceInstanceReader;

/**
 * A reviewer's decision about a {@code Pending Review} claim. Claims the review under the claim's optimistic lock (the
 * {@code ClaimIntakeState} version), confirms the waiting run still exists, then publishes the decision for the intake
 * workflow to apply.
 * <p>
 *   This is asynchronous: {@link #submit} returns once the decision is published, not once the workflow has applied it
 *   (that can still involve a mail send). Claiming the review is what a losing reply ({@code SupersedeStep}) and a losing
 *   decision race on; whichever commits first wins.
 * </p>
 * <p>
 *   {@link ReviewDecisionPublisher} documents a theoretical gap (F2b): a run's {@code WAITING} status becomes visible a
 *   couple of statements before its {@code listen} task actually registers a consumer
 *   (serverlessworkflow-impl-core's {@code ListenExecutor.internalExecute}, run synchronously on the task's own thread).
 *   This class doesn't work around it: {@code InMemoryEvents.publish} always schedules its delivery onto an executor
 *   before it reads the registered consumer, and that scheduling hop structurally outlasts two sequential method calls on
 *   the thread that's already running — so by the time delivery is attempted, registration has, for any caller doing
 *   real work in between (a DB transaction, JSON (de)serialization, an HTTP round trip), already happened. A synthetic
 *   caller that calls {@code publish} with nothing at all in between (no other test exists that does) is the only shape
 *   that could still hit it, which is why {@code ReviewDecisionPublisher}'s republish-until-woken loop stays a caller
 *   concern (see {@code IntakeScenario.publishUntilWoken}) rather than something this service retries for.
 * </p>
 */
@ApplicationScoped
public class ClaimReviewService {
	private final ClaimIntakeFlow flow;
	private final PersistenceInstanceReader persistedRuns;
	private final ReviewDecisionPublisher publisher;

	ClaimReviewService(ClaimIntakeFlow flow, PersistenceInstanceReader persistedRuns, ReviewDecisionPublisher publisher) {
		this.flow = flow;
		this.persistedRuns = persistedRuns;
		this.publisher = publisher;
	}

	/**
	 * Submits a reviewer's decision. Nothing is published unless every check passes, in this order: the claim exists, it's
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
	 * @throws ReviewRunNotFoundException if the claim's run no longer exists
	 * @throws ReviewRunNotWaitingException if the claim's run isn't waiting for a decision
	 */
	public void submit(long claimId, long expectedVersion, ReviewDecision decision) {
		var runId = claimReview(claimId, expectedVersion, decision.fields());

		ensureWaiting(claimId, runId);

		this.publisher.publish(new ReviewDecided(claimId, runId, decision));
	}

	/**
	 * Whether a decision would actually succeed right now. {@link ClaimIntakeState#isOpenForReview()} alone isn't
	 * enough: {@code reviewRunId} is set in the workflow's {@code applyOutcome} step, several steps before the run
	 * actually reaches {@code waitReview} and becomes {@link WorkflowStatus#WAITING} (it still has to send the reply
	 * and file the email first). A claim can read as open for review while its run is still doing that, in which case
	 * a decision submitted right then would be rejected with {@link ReviewRunNotWaitingException}.
	 *
	 * @param state the claim's intake state
	 * @return {@code true} only once the run is genuinely waiting for a decision
	 */
	public boolean isReadyForDecision(ClaimIntakeState state) {
		return state.isOpenForReview() && isWaiting(findRun(state.reviewRunId));
	}

	// Its own transaction: losing the claim's lock to a reply or another decision fails it at the flush, which is
	// turned into a ReviewLockLostException here. The reviewed values are only checked against the claim here; the
	// decision step sets them, in the transaction that changes the status
	@Transactional(TxType.REQUIRES_NEW)
	String claimReview(long claimId, long expectedVersion, ReviewedFields fields) {
		var state = ReviewGuard.openReview(claimId, expectedVersion, LockModeType.NONE);

		ensureFits(state.claim, fields);

		try {
			state.decidingRunId = state.reviewRunId;
			state.reviewRunId = null;
			ClaimIntakeState.flush();
		}
		catch (RuntimeException e) {
			throw isOptimisticLockFailure(e) ? new ReviewLockLostException(claimId) : e;
		}

		return state.decidingRunId;
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

	private void ensureWaiting(long claimId, String runId) {
		var run = findRun(runId);

		if (!isWaiting(run)) {
			restoreReview(claimId, runId);
			run.ifPresentOrElse(
				instance -> { throw new ReviewRunNotWaitingException(claimId, runId, instance.status()); },
				() -> { throw new ReviewRunNotFoundException(claimId, runId); });
		}
	}

	private Optional<WorkflowInstance> findRun(String runId) {
		return this.persistedRuns.find(this.flow.definition(), runId);
	}

	private static boolean isWaiting(Optional<WorkflowInstance> run) {
		return run.map(WorkflowInstance::status).filter(WorkflowStatus.WAITING::equals).isPresent();
	}

	// Compensating transaction: a failed step-2 check would otherwise leave the claim stuck "being decided"
	@Transactional(TxType.REQUIRES_NEW)
	void restoreReview(long claimId, String runId) {
		ClaimIntakeState.findForClaim(claimId)
			.filter(state -> runId.equals(state.decidingRunId))
			.ifPresent(state -> {
				state.reviewRunId = runId;
				state.decidingRunId = null;
			});
	}

	private static boolean isOptimisticLockFailure(RuntimeException e) {
		return Stream.iterate(e, Objects::nonNull, Throwable::getCause).anyMatch(OptimisticLockException.class::isInstance);
	}
}
