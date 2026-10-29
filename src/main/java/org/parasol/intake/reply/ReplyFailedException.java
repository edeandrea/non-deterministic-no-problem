package org.parasol.intake.reply;

/**
 * Sending a reply failed or timed out. It names the reply as what failed, whatever the mailer's own exception or timeout was.
 */
public class ReplyFailedException extends RuntimeException {
	ReplyFailedException(Throwable cause) {
		super("Couldn't send the reply", cause);
	}
}
