package org.parasol.intake.mailbox;

/**
 * An email the watcher has moved from the INBOX to the processing folder and now hands over for intake. It's fired as a
 * synchronous CDI event, on the watcher's thread, so a sender's emails reach their observers in the order they arrived.
 *
 * @param email The email
 * @param isRecovery Whether a previous leader left the email in the processing folder, so it may already have been accepted, applied or answered
 */
public record EmailReceived(InboundEmail email, boolean isRecovery) {
	/**
	 * An email that has just come in from the INBOX.
	 *
	 * @param email The email
	 */
	public EmailReceived(InboundEmail email) {
		this(email, false);
	}
}
