package org.parasol.intake.workflow.review;

import java.util.Optional;
import java.util.function.BiFunction;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimCorrespondence;
import org.parasol.claim.model.ClaimNotFoundException;
import org.parasol.claim.model.CorrespondenceDirection;
import org.parasol.claim.model.IntakeClaimStatus;
import org.parasol.intake.model.ClaimIntakeState;
import org.parasol.intake.workflow.ClaimIntakeFlow;
import org.parasol.intake.workflow.IntakeRun;

import io.quarkus.logging.Log;

/**
 * Keeps a claim's review alive when the run that owns it faults or is gone ({@link #reclaim}).
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

	ReviewRecoverer(Instance<ClaimIntakeFlow> flow) {
		this.flow = flow;
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
			.map(review -> handOver(review, faultedRunId, review.conversationIdOr(faultedRun.conversationId()), (conversationId, claimId) -> IntakeRun.resumingReview(faultedRun, conversationId, claimId)))
			.orElse(ReviewHandover.NOT_NEEDED);
	}

	/**
	 * Hands the review of a run that's gone (its application instance died or restarted) to a new run. The run's input isn't available, so the
	 * new run's is built from what the intake itself recorded about the claim. A decision the reviewer already made for the gone run stays the
	 * claim's decision: it's re-addressed to the new run, which applies it as soon as it waits.
	 *
	 * @param orphanedRunId The id of the run that owned the review
	 * @return What became of the review
	 */
	public ReviewHandover reclaim(String orphanedRunId) {
		return reviewOwnedBy(orphanedRunId)
			.map(review -> handOver(review, orphanedRunId, review.conversationIdOr(null), this::reclaimingRun))
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

	// The email the new run answers on is the claim's latest inbound one, which is what a decision's reply threads under
	@Transactional(TxType.REQUIRES_NEW)
	IntakeRun reclaimingRun(String conversationId, long claimId) {
		var claim = Claim.<Claim>findByIdOptional(claimId).orElseThrow(() -> new ClaimNotFoundException(claimId));
		var messageId = ClaimCorrespondence.listForClaim(claim).stream()
			.filter(correspondence -> correspondence.direction == CorrespondenceDirection.INBOUND)
			.reduce((first, second) -> second)
			.map(correspondence -> correspondence.messageId)
			.orElseThrow(() -> new IllegalStateException("Claim %d has no inbound email to resume its review from".formatted(claimId)));

		return IntakeRun.resumingReview(messageId, claim.emailAddress, conversationId, claimId);
	}

	// The new run is registered as the claim's review run before it starts, so a decision published as soon as it waits
	// matches it
	private ReviewHandover handOver(OwnedReview review, String orphanedRunId, String conversationId, BiFunction<String, Long, IntakeRun> runFor) {
		var instance = this.flow.get().newRun(runFor.apply(conversationId, review.claimId()));

		// The instance has to exist before the claim can be taken over, because its id is what's written as the new
		// reviewRunId. If the take-over then loses, the instance is never started and stays in Flow's in-memory
		// activeInstances map: cancel() is a no-op on a PENDING instance and removeInstance isn't public
		// (open-workflow-specification/sdk-java#1816). Harmless — the id was never persisted, so nothing looks it up —
		// but it is a leaked map entry. Discard the instance here once that issue is resolved.
		var hasTakenOver = takeOver(review.claimId(), orphanedRunId, instance.id());

		if (hasTakenOver) {
			instance.start();
			Log.warnf("Run %s no longer owns the review of claim %d: run %s now waits for the decision", orphanedRunId, review.claimId(), instance.id());
		}
		else {
			Log.warnf("Run %s no longer owns the review of claim %d, but another run already holds it", orphanedRunId, review.claimId());
		}

		return hasTakenOver ? ReviewHandover.DONE : ReviewHandover.ALREADY_ELSEWHERE;
	}

	// Under the intake state's optimistic lock: only if the old run still owns the review. A decision the reviewer already claimed for the
	// old run (decidingRunId) isn't kept: it was only in that run's memory, so the review goes back to open for the new run
	// Non-private and non-final, so the transaction interceptor applies to the call from this class
	@Transactional(TxType.REQUIRES_NEW)
	boolean takeOver(long claimId, String orphanedRunId, String newRunId) {
		return ClaimIntakeState.findForClaim(claimId)
			.filter(state -> orphanedRunId.equals(state.reviewRunId) || orphanedRunId.equals(state.decidingRunId))
			.map(state -> {
				state.reviewRunId = newRunId;
				state.decidingRunId = null;

				return true;
			})
			.orElse(false);
	}

	record OwnedReview(long claimId, String conversationId) {
		String conversationIdOr(String fallback) {
			return Optional.ofNullable(this.conversationId).orElse(fallback);
		}
	}
}
