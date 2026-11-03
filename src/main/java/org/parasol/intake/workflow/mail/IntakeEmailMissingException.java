package org.parasol.intake.workflow.mail;

/**
 * A step couldn't find its email in the processing folder.
 */
class IntakeEmailMissingException extends RuntimeException {
	IntakeEmailMissingException(String messageId) {
		super("No email with Message-ID %s is in the processing folder".formatted(messageId));
	}
}
