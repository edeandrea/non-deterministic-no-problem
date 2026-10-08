package org.parasol.intake.mailbox;

/**
 * Reading or changing the claims mailbox failed.
 */
public class MailboxException extends RuntimeException {
	MailboxException(String message, Throwable cause) {
		super(message, cause);
	}
}