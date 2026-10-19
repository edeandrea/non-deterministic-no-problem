package org.parasol.intake.workflow.mail;

import jakarta.enterprise.context.ApplicationScoped;

import org.parasol.intake.mailbox.ClaimsMailbox;
import org.parasol.intake.mailbox.InboundEmail;
import org.parasol.intake.mailbox.MailFolder;

/**
 * Re-reads the email a run is working on. The run carries only its {@code Message-ID}, so Flow never persists the email.
 */
@ApplicationScoped
public class IntakeEmailReader {
	private final ClaimsMailbox mailbox;

	IntakeEmailReader(ClaimsMailbox mailbox) {
		this.mailbox = mailbox;
	}

	public InboundEmail require(String messageId) {
		return this.mailbox.find(MailFolder.PROCESSING, messageId)
			.orElseThrow(() -> new IntakeEmailMissingException(messageId));
	}
}
