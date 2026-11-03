package org.parasol.intake.workflow;

import java.time.Instant;
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
 * @param receivedAt When the mailbox received the email, as an ISO-8601 instant, or {@code null} if unknown (a run persisted before
 *   this was recorded still reads). The email's latency is measured from it
 */
public record IntakeRun(String messageId, String conversationId, String sender, Long claimId, boolean senderMatches, boolean resumesReview, String receivedAt) {
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
	 * @param receivedAt When the mailbox received the email
	 * @return The run's input
	 */
	public static IntakeRun forEmail(String messageId, String conversationId, String sender, Long claimId, boolean senderMatches, Instant receivedAt) {
		return new IntakeRun(messageId, conversationId, sender, claimId, senderMatches, false, receivedAt.toString());
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
		return new IntakeRun(faulted.messageId(), conversationId, faulted.sender(), claimId, true, true, faulted.receivedAt());
	}
}
