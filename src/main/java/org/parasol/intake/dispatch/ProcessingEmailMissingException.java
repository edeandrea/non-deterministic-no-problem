package org.parasol.intake.dispatch;

/**
 * An accepted email disappeared from the processing folder before the dispatcher could start its run.
 */
class ProcessingEmailMissingException extends RuntimeException {
	ProcessingEmailMissingException(String messageId) {
		// The missing email is the whole diagnosis; no stack trace is needed for this domain failure.
		super("No email with Message-ID %s is in the processing folder".formatted(messageId), null, false, false);
	}
}