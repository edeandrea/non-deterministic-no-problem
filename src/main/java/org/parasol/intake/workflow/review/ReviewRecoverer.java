package org.parasol.intake.workflow.review;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import org.parasol.claim.model.IntakeClaimStatus;
import org.parasol.intake.model.ClaimIntakeState;
import org.parasol.intake.workflow.ClaimIntakeFlow;
import org.parasol.intake.workflow.IntakeRun;

import ai.scoring.conversation.ConversationContext;
import io.quarkus.logging.Log;

/**
 * Keeps a claim's review alive when the run that owns it faults.
 * <p>
 *   A run owns a claim's review once it has set itself as the {@code reviewRunId} (or the reviewer has claimed it, as the
 *   {@code decidingRunId}). If that run faults outside the decision steps' own catch, the claim would stay
 *   {@code Pending Review} with nothing left to receive the reviewer's decision. Instead a new run takes over: it goes
 *   straight to the review wait ({@link IntakeRun#resumesReview()}), and becomes the claim's {@code reviewRunId} before it
 *   starts, so a decision can never miss it.
 * </p>
 */
@ApplicationScoped
public class ReviewRecoverer {
	private final Instance<ClaimIntakeFlow> flow;
	private final ConversationContext conversationContext;

	ReviewRecoverer(Instance<ClaimIntakeFlow> flow, ConversationContext conversationContext) {
		this.flow = flow;
		this.conversationContext = conversationContext;
	}

	/**
	 * Hands the review of a faulted run's claim to a new run, if the faulted run owned one.
	 *
	 * @param faultedRunId The id of the run that faulted
	 * @param faultedRun The faulted run's input
	 * @return What became of the review, which tells the caller whether the claim is still being handled
	 */
	public ReviewHandover recover(String faultedRunId, IntakeRun faultedRun) {
		return reviewOwnedBy(faultedRunId)
			.map(review -> handOver(review, faultedRunId, faultedRun))
			.orElse(ReviewHandover.NOT_NEEDED);
	}

	// Non-private and non-final, so the transaction interceptor applies to the call from this class
	@Transactional(TxType.REQUIRES_NEW)
	Optional<OwnedReview> reviewOwnedBy(String runId) {
		return ClaimIntakeState.<ClaimIntakeState>find("reviewRunId = ?1 or decidingRunId = ?1", runId)
			.firstResultOptional()
			.filter(state -> IntakeClaimStatus.PENDING_REVIEW.label().equals(state.claim.status))
			.map(state -> new OwnedReview(state.id, state.intakeConversationId));
	}

	// The new run is registered as the claim's review run before it starts, so a decision published as soon as it waits
	// matches it
	private ReviewHandover handOver(OwnedReview review, String faultedRunId, IntakeRun faultedRun) {
		var conversationId = Optional.ofNullable(review.conversationId()).orElse(faultedRun.conversationId());

		return this.conversationContext.callIn(conversationId, () -> {
			var instance = this.flow.get().instance(IntakeRun.resumingReview(faultedRun, conversationId, review.claimId()));

			// The instance has to exist before the claim can be taken over, because its id is what's written as the new
			// reviewRunId. If the take-over then loses, the instance is never started and stays in Flow's in-memory
			// activeInstances map: cancel() is a no-op on a PENDING instance and removeInstance isn't public
			// (open-workflow-specification/sdk-java#1816). Harmless — the id was never persisted, so nothing looks it up —
			// but it is a leaked map entry. Discard the instance here once that issue is resolved.
			if (takeOver(review.claimId(), faultedRunId, instance.id())) {
				instance.start();
				Log.warnf("Run %s faulted while it owned the review of claim %d: run %s now waits for the decision", faultedRunId, review.claimId(), instance.id());

				return ReviewHandover.DONE;
			}

			Log.warnf("Run %s faulted while it owned the review of claim %d, but another run already holds it", faultedRunId, review.claimId());

			return ReviewHandover.ALREADY_ELSEWHERE;
		});
	}

	// Under the intake state's optimistic lock: only if the faulted run still owns the review
	// Non-private and non-final, so the transaction interceptor applies to the call from this class
	@Transactional(TxType.REQUIRES_NEW)
	boolean takeOver(long claimId, String faultedRunId, String newRunId) {
		return ClaimIntakeState.findForClaim(claimId)
			.filter(state -> faultedRunId.equals(state.reviewRunId) || faultedRunId.equals(state.decidingRunId))
			.map(state -> {
				state.reviewRunId = newRunId;
				state.decidingRunId = null;

				return true;
			})
			.orElse(false);
	}

	record OwnedReview(long claimId, String conversationId) {
	}
}
