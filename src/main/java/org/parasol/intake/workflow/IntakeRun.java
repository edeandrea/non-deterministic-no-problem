package org.parasol.intake.workflow;

import java.util.Objects;

/**
 * The input of a {@link ClaimIntakeFlow} run: ids only, never the email, so Flow's persisted run data holds no email
 * text or attachment bytes.
 *
 * @param messageId The {@code Message-ID} of the email in the processing folder
 * @param conversationId The conversation id the run was started in
 * @param sender The sender's address, as it was written
 * @param claimId The claim the starter matched the email to, or {@code null} if it matched none
 * @param senderMatches Whether the sender is the matched claim's address. Meaningless without a claim
 * @param resumesReview Whether this run only takes over a claim's review from a run that faulted: it goes straight to the
 *   review wait, and its {@code messageId} is the email the faulted run worked on
 */
public record IntakeRun(String messageId, String conversationId, String sender, Long claimId, boolean senderMatches, boolean resumesReview) {
	public IntakeRun {
		Objects.requireNonNull(messageId, "messageId");
		Objects.requireNonNull(conversationId, "conversationId");
		Objects.requireNonNull(sender, "sender");
	}

	/**
	 * A run for an email the starter matched (or didn't).
	 *
	 * @param messageId The {@code Message-ID} of the email in the processing folder
	 * @param conversationId The conversation id the run is started in
	 * @param sender The sender's address
	 * @param claimId The matched claim, or {@code null}
	 * @param senderMatches Whether the sender is the matched claim's address
	 * @return The run's input
	 */
	public static IntakeRun forEmail(String messageId, String conversationId, String sender, Long claimId, boolean senderMatches) {
		return new IntakeRun(messageId, conversationId, sender, claimId, senderMatches, false);
	}

	/**
	 * A run that takes over a claim's review from a run that faulted.
	 *
	 * @param faulted The faulted run's input
	 * @param conversationId The claim's conversation id
	 * @param claimId The claim waiting for review
	 * @return The run's input
	 */
	public static IntakeRun resumingReview(IntakeRun faulted, String conversationId, long claimId) {
		return new IntakeRun(faulted.messageId(), conversationId, faulted.sender(), claimId, true, true);
	}
}
