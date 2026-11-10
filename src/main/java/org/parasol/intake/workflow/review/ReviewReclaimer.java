package org.parasol.intake.workflow.review;

import static java.util.function.Predicate.not;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import org.parasol.claim.model.ClaimCorrespondence;
import org.parasol.claim.model.CorrespondenceDirection;
import org.parasol.claim.model.IntakeClaimStatus;
import org.parasol.intake.leadership.LeadershipAcquired;
import org.parasol.intake.mailbox.ClaimsMailbox;
import org.parasol.intake.mailbox.MailFolder;
import org.parasol.intake.model.ClaimIntakeState;
import org.parasol.intake.observability.IntakeActivities;
import org.parasol.intake.observability.IntakeActivity.ReviewReclaimed;
import org.parasol.intake.workflow.ClaimIntakeFlow;

import io.quarkus.logging.Log;
import io.quarkus.virtual.threads.VirtualThreads;
import io.serverlessworkflow.impl.WorkflowStatus;

/**
 * Gives every review whose run is gone a live run again. A run lives in one JVM, so when the leader dies or the only application instance
 * restarts, every claim left in {@code Pending Review} names a run nobody hosts. The new leader hands each one to a new run
 * ({@link ReviewRecoverer#reclaim}), which goes straight to the review wait: it never re-sends the review reply or re-files the email.
 * <p>
 *   Flow's own restore is off ({@code quarkus.flow.persistence.auto-restore}): it would replay a run from its first incomplete task on every
 *   application instance. The dead run's persisted rows are left in Flow's tables, which the app never writes to; with nothing restoring them
 *   they're inert.
 * </p>
 * <p>
 *   A claim whose latest email is still in the processing folder is left to the processing recovery ({@code ClaimIntakeDispatcher#recover}): its
 *   resumed run replies, files the email and becomes the claim's review run.
 * </p>
 */
@ApplicationScoped
public class ReviewReclaimer {
	private final ClaimIntakeFlow flow;
	private final ReviewRecoverer recoverer;
	private final ExecutorService executor;
	private final IntakeActivities activities;
	private final ClaimsMailbox mailbox;

	ReviewReclaimer(ClaimIntakeFlow flow, ReviewRecoverer recoverer, @VirtualThreads ExecutorService executor, IntakeActivities activities, ClaimsMailbox mailbox) {
		this.flow = flow;
		this.recoverer = recoverer;
		this.executor = executor;
		this.activities = activities;
		this.mailbox = mailbox;
	}

	void onLeadershipAcquired(@Observes LeadershipAcquired event) {
		this.executor.execute(this::reclaim);
	}

	/**
	 * Hands every review whose run isn't hosted in this JVM to a new run. A failure for one claim is logged and doesn't stop the others.
	 *
	 * @return how many reviews were handed over
	 */
	public int reclaim() {
		try {
			var reclaimed = (int) orphanedReviews().stream()
				.filter(review -> !isHostedHere(review.runId()))
				.filter(not(this::isBeingRecovered))
				.map(OrphanedReview::runId)
				.filter(this::reclaim)
				.count();

			Log.debugf("Reclaimed %d reviews", reclaimed);

			return reclaimed;
		}
		catch (RuntimeException e) {
			Log.errorf(e, "Couldn't reclaim the reviews of runs that are gone");

			return 0;
		}
	}

	private boolean reclaim(String runId) {
		try {
			var isDone = this.recoverer.reclaim(runId) == ReviewHandover.DONE;

			if (isDone) {
				this.activities.publish(new ReviewReclaimed());
			}

			return isDone;
		}
		catch (RuntimeException e) {
			Log.errorf(e, "Couldn't reclaim the review of run %s", runId);

			return false;
		}
	}

	// The run a claim's review is with: the one deciding it if a decision was claimed, else the one waiting for it
	// Non-private and non-final, so the transaction interceptor applies to the call from this class
	@Transactional(TxType.REQUIRES_NEW)
	List<OrphanedReview> orphanedReviews() {
		return ClaimIntakeState.<ClaimIntakeState>list("claim.status", IntakeClaimStatus.PENDING_REVIEW.label()).stream()
			.filter(state -> (state.decidingRunId != null) || (state.reviewRunId != null))
			.map(state -> new OrphanedReview(
				(state.decidingRunId != null) ? state.decidingRunId : state.reviewRunId,
				ClaimCorrespondence.listForClaim(state.claim).stream()
					.filter(correspondence -> correspondence.direction == CorrespondenceDirection.INBOUND)
					.reduce((first, second) -> second)
					.map(correspondence -> correspondence.messageId)
					.orElse(null)))
			.toList();
	}

	// A claim whose latest email is still in the processing folder hasn't been answered or filed: the processing recovery owns it, and its
	// resumed run becomes the claim's review run. Taking it here too would start a second run for the same review
	private boolean isBeingRecovered(OrphanedReview review) {
		return Optional.ofNullable(review.latestInboundMessageId())
			.flatMap(messageId -> this.mailbox.find(MailFolder.PROCESSING, messageId))
			.isPresent();
	}

	private record OrphanedReview(String runId, String latestInboundMessageId) {
	}

	// A PENDING instance is a stand-in Flow leaves behind for a run it doesn't host, and an ended one has nothing left to wait for
	@SuppressWarnings("resource")
	private boolean isHostedHere(String runId) {
		return this.flow.definition().activeInstance(runId)
			.map(run -> (run.status() == WorkflowStatus.RUNNING) || (run.status() == WorkflowStatus.WAITING))
			.orElse(false);
	}
}
