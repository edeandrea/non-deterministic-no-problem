package org.parasol.intake.dispatch;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import org.parasol.claim.model.ClaimCorrespondence;
import org.parasol.claim.model.IntakeClaimStatus;
import org.parasol.intake.model.ClaimIntakeState;

/**
 * Tells how far a run that died got with an email a previous instance left in the processing folder, from what the intake itself
 * recorded about it (never from the workflow engine's tables): the email's {@code ClaimCorrespondence} row, the claim it's linked to
 * and whether an outbound row answers it.
 * <p>
 *   One gap stays open on purpose: an application instance that dies between the SMTP send and {@code ReplyRecorder}'s commit (or whose
 *   recording failed with only a warning) leaves no outbound row, so the customer gets that reply twice.
 * </p>
 */
@ApplicationScoped
class ProcessingRecovery {
	/**
	 * How far an email got.
	 */
	enum Stage {
		/**
		 * Never accepted: the normal path.
		 */
		NEW,

		/**
		 * Accepted, but nothing applied to a claim (or only a status question linked): runs again from the start, without the duplicate check.
		 */
		NOT_APPLIED,

		/**
		 * Applied to its claim, but no reply is recorded: resumes at the reply.
		 */
		APPLIED_UNANSWERED,

		/**
		 * Applied and answered: only filing was left.
		 */
		ANSWERED
	}

	/**
	 * What recovery found.
	 *
	 * @param stage How far the email got
	 * @param claimId The claim the email was applied to, or {@code null}
	 * @param conversationId The claim's conversation id, or {@code null}
	 * @param createdClaim Whether the email created its claim
	 * @param mergedIntoClaim Whether the email was merged into its claim
	 */
	record Assessment(Stage stage, Long claimId, String conversationId, boolean createdClaim, boolean mergedIntoClaim) {
		static final Assessment NEW = new Assessment(Stage.NEW, null, null, false, false);
	}

	// Non-private and non-final, so the transaction interceptor applies to the call from this class
	@Transactional(TxType.REQUIRES_NEW)
	Assessment assess(String messageId) {
		return ClaimCorrespondence.findByMessageId(messageId)
			.map(ProcessingRecovery::assess)
			.orElse(Assessment.NEW);
	}

	private static Assessment assess(ClaimCorrespondence row) {
		var claim = Optional.ofNullable(row.claim);

		if (claim.isEmpty()) {
			return new Assessment(Stage.NOT_APPLIED, null, null, false, false);
		}

		var claimId = claim.get().id;
		var conversationId = ClaimIntakeState.findForClaim(claimId).map(state -> state.intakeConversationId).orElse(null);
		var isApplied = row.createdClaim || row.mergedIntoClaim;
		var isAnswered = ClaimCorrespondence.hasReplyTo(row.messageId);
		var stage = isAnswered ? Stage.ANSWERED : (isApplied ? Stage.APPLIED_UNANSWERED : Stage.NOT_APPLIED);

		return new Assessment(stage, claimId, conversationId, row.createdClaim, row.mergedIntoClaim);
	}

	/**
	 * Makes a run that resumes at the reply the claim's review run, if the dead run had set itself as that, so a decision and the reclaim of
	 * orphaned reviews both find the live run.
	 *
	 * @param claimId The claim
	 * @param runId The resuming run's id
	 */
	// Non-private and non-final, so the transaction interceptor applies to the call from this class
	@Transactional(TxType.REQUIRES_NEW)
	void adoptReview(long claimId, String runId) {
		ClaimIntakeState.findForClaim(claimId)
			.filter(state -> IntakeClaimStatus.PENDING_REVIEW.label().equals(state.claim.status))
			.filter(state -> state.reviewRunId != null)
			.ifPresent(state -> {
				state.reviewRunId = runId;
				state.decidingRunId = null;
			});
	}
}
