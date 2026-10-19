package org.parasol.intake.workflow.review;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional.TxType;
import jakarta.transaction.Transactional;

import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimImage;
import org.parasol.intake.IntakeClaimStatus;
import org.parasol.intake.model.ClaimIntakeState;
import org.parasol.intake.reply.IntakeReplySender;
import org.parasol.intake.reply.IntakeTemplates;
import org.parasol.intake.reply.ThreadingHeaders;
import org.parasol.intake.review.ReviewDecided;
import org.parasol.intake.workflow.IntakeRun;

import io.quarkus.logging.Log;
import io.quarkus.mailer.MailTemplate.MailTemplateInstance;
import io.serverlessworkflow.impl.WorkflowError;
import io.serverlessworkflow.impl.WorkflowException;

/**
 * The workflow's steps after a reviewer's decision, and the review wait's check that a decision is for its run. They make
 * no LLM call.
 * <p>
 *   Each commits the status change first and sends the email after it, so a send that fails after the commit only logs a
 *   problem (the claim has moved on; a retry would mean a second email). A fault before the commit puts the claim back for
 *   the reviewer to retry: {@link #restoreReview}.
 * </p>
 */
@ApplicationScoped
public class ReviewDecisionSteps {
	/**
	 * The error type of a decision that couldn't be applied. The flow catches it and puts the claim back in review.
	 */
	public static final String DECISION_FAILED = "https://parasol.org/errors/intake-decision-failed";

	private final IntakeReplySender sender;

	ReviewDecisionSteps(IntakeReplySender sender) {
		this.sender = sender;
	}

	public AfterDecision markInProcess(ReviewDecided decided, IntakeRun run, String runId) {
		var claim = committing(() -> applyReady(decided, runId));

		sendAfterCommit(run, claim, IntakeTemplates.claimInProcess(Optional.ofNullable(claim.clientName()), claim.claimNumber()));

		return AfterDecision.END;
	}

	public AfterDecision markPendingInformation(ReviewDecided decided, IntakeRun run, String runId) {
		var claim = committing(() -> applyNeedsInformation(decided, runId));
		var items = decided.decision().missingItems().isEmpty()
			? List.<org.parasol.intake.MissingItem>of()
			: List.copyOf(java.util.EnumSet.copyOf(decided.decision().missingItems()));

		sendAfterCommit(run, claim,
			IntakeTemplates.missingInformation(Optional.ofNullable(claim.clientName()), claim.claimNumber(), items, claim.hasPhotos(), List.of()));

		return AfterDecision.END;
	}

	@Transactional(TxType.REQUIRES_NEW)
	public Snapshot applyReady(ReviewDecided decided, String runId) {
		var intake = stateBeingDecided(decided, runId);
		intake.claim.status = IntakeClaimStatus.IN_PROCESS.label();
		intake.decidingRunId = null;
		intake.requestedItems = new HashSet<>();

		return snapshot(intake.claim);
	}

	@Transactional(TxType.REQUIRES_NEW)
	public Snapshot applyNeedsInformation(ReviewDecided decided, String runId) {
		var intake = stateBeingDecided(decided, runId);
		intake.claim.status = IntakeClaimStatus.PENDING_INFORMATION.label();
		intake.decidingRunId = null;
		intake.requestedItems = new HashSet<>(decided.decision().missingItems());

		return snapshot(intake.claim);
	}

	/**
	 * Whether a decision is for a run: the run must be the claim's review run (waiting, or claimed by the reviewer) and the
	 * claim still {@code Pending Review}. The flow's review wait uses it to ignore decisions for other runs.
	 *
	 * @param claimId the claim the decision names
	 * @param runId the run that's waiting
	 * @return {@code true} if the decision is for that run
	 */
	@Transactional(TxType.REQUIRES_NEW)
	public boolean isDecisionFor(long claimId, String runId) {
		return ClaimIntakeState.findForClaim(claimId)
			.filter(state -> runId.equals(state.decidingRunId) || runId.equals(state.reviewRunId))
			.filter(state -> IntakeClaimStatus.PENDING_REVIEW.label().equals(state.claim.status))
			.isPresent();
	}

	// Flow's try/catch only catches a WorkflowException, never a plain Java exception, so a fault before the commit is
	// rethrown as one, with a type the flow's catch names
	private static Snapshot committing(Supplier<Snapshot> commit) {
		try {
			return commit.get();
		}
		catch (RuntimeException e) {
			throw new WorkflowException(WorkflowError.error(DECISION_FAILED, 500).title("The decision couldn't be applied").details(e.getMessage()).build(), e);
		}
	}

	/**
	 * Puts a claim back in review after a fault in a decision step, so the reviewer can decide again and the same run waits
	 * again. If the claim's status has already changed there's nothing to put back: it only logs.
	 */
	public AfterDecision restoreReview(String runId) {
		var restored = putBackInReview(runId);

		restored.ifPresentOrElse(
			number -> Log.warnf("A decision step failed for claim %s: it's back in review and run %s waits for the next decision", number, runId),
			() -> Log.errorf("A decision step failed for run %s after the claim's status changed: nothing to put back", runId));

		return restored.isPresent() ? AfterDecision.WAIT_AGAIN : AfterDecision.END;
	}

	// Committed before restoreReview logs that the claim is back in review
	@Transactional(TxType.REQUIRES_NEW)
	Optional<String> putBackInReview(String runId) {
		return ClaimIntakeState.<ClaimIntakeState>find("decidingRunId", runId)
			.firstResultOptional()
			.filter(intake -> IntakeClaimStatus.PENDING_REVIEW.label().equals(intake.claim.status))
			.map(intake -> {
				intake.reviewRunId = intake.decidingRunId;
				intake.decidingRunId = null;

				return intake.claim.claimNumber;
			});
	}

	private static ClaimIntakeState stateBeingDecided(ReviewDecided decided, String runId) {
		return ClaimIntakeState.findForClaim(decided.claimId())
			.filter(intake -> runId.equals(intake.decidingRunId))
			.filter(intake -> IntakeClaimStatus.PENDING_REVIEW.label().equals(intake.claim.status))
			.orElseThrow(() -> new IllegalStateException("Claim %d isn't being decided by run %s".formatted(decided.claimId(), runId)));
	}

	private static Snapshot snapshot(Claim claim) {
		return new Snapshot(claim.claimNumber, claim.clientName, claim.emailAddress, claim.subject, ClaimImage.count("claim", claim) > 0);
	}

	private void sendAfterCommit(IntakeRun run, Snapshot claim, MailTemplateInstance template) {
		try {
			this.sender.sendTemplateReply(template, claim.emailAddress(), claim.subject(), claim.claimNumber(), ThreadingHeaders.replyingTo(run.messageId()));
		}
		catch (RuntimeException e) {
			Log.errorf(e, "The decision for claim %s was applied, but its email couldn't be sent", claim.claimNumber());
		}
	}

	public record Snapshot(String claimNumber, String clientName, String emailAddress, String subject, boolean hasPhotos) {
	}
}
