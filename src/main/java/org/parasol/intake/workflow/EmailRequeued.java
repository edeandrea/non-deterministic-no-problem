package org.parasol.intake.workflow;

/**
 * Fired when a run finds its claim being decided and hands the email back: the starter puts it at the front of the
 * sender's queue, to be handled again under the claim's new status.
 *
 * @param messageId The {@code Message-ID} of the email, still in the processing folder
 * @param sender The sender's address
 */
public record EmailRequeued(String messageId, String sender) {
}
