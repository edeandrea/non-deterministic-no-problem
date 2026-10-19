package org.parasol.intake.workflow.mail;

import jakarta.enterprise.context.ApplicationScoped;

import org.parasol.intake.mailbox.ClaimsMailbox;
import org.parasol.intake.mailbox.MailFolder;
import org.parasol.intake.workflow.IntakeState;

import io.quarkus.logging.Log;

/**
 * The workflow's {@code file} step: moves the email from the processing folder to the processed one, also when the run is
 * about to wait for review.
 */
@ApplicationScoped
public class FileEmailStep {
	private final ClaimsMailbox mailbox;

	FileEmailStep(ClaimsMailbox mailbox) {
		this.mailbox = mailbox;
	}

	public IntakeState file(IntakeState state) {
		var isMoved = this.mailbox.move(state.messageId(), MailFolder.PROCESSING, MailFolder.PROCESSED);

		if (!isMoved) {
			Log.warnf("%s wasn't in the processing folder to file", state.messageId());
		}

		return state;
	}
}
