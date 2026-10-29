package org.parasol.intake.mailbox;

/**
 * Changing the claims mailbox, such as moving an email between folders, failed.
 */
public class MailboxMoveException extends MailboxException {
	MailboxMoveException(String message, Throwable cause) {
		super(message, cause);
	}
}
