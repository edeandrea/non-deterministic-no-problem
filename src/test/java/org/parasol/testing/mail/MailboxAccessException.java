package org.parasol.testing.mail;

/**
 * Reading a GreenMail mailbox failed.
 */
public class MailboxAccessException extends RuntimeException {
	MailboxAccessException(String message, Throwable cause) {
		super(message, cause);
	}

	MailboxAccessException(String message) {
		super(message);
	}
}