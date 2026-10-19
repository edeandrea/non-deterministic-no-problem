package org.parasol.intake.failure;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import org.parasol.intake.IntakeConfig;
import org.parasol.intake.mailbox.InboundEmail;
import org.parasol.intake.mailbox.MailLoopGuard;
import org.parasol.intake.reply.CustomerName;
import org.parasol.intake.reply.IntakeReplySender;
import org.parasol.intake.reply.IntakeTemplates;
import org.parasol.intake.reply.ThreadingHeaders;

import io.quarkus.logging.Log;

/**
 * Sends the "we couldn't process your email" reply. Best-effort: it never throws, so a run that's already failing can't also
 * fail its failure handling, and it's never sent for auto-replies, mail from the claims address, or mail with no sender.
 */
@ApplicationScoped
public class ProcessingProblemReplySender {
	private final IntakeConfig config;
	private final IntakeReplySender sender;

	ProcessingProblemReplySender(IntakeConfig config, IntakeReplySender sender) {
		this.config = config;
		this.sender = sender;
	}

	/**
	 * Sends the reply.
	 *
	 * @param email The email that couldn't be processed
	 */
	public void send(InboundEmail email) {
		var isThreadable = email.messageId().isPresent();

		if (MailLoopGuard.shouldIgnore(email, this.config.address())) {
			Log.debugf("Not answering %s with a processing problem: it's automatic or self-sent", email.messageId().orElse("an email without a Message-ID"));
		}
		else {
			email.fromAddress().ifPresent(address -> trySend(email, address, isThreadable));
		}
	}

	private void trySend(InboundEmail email, String address, boolean isThreadable) {
		try {
			this.sender.sendTemplateReply(
				IntakeTemplates.processingProblem(CustomerName.forReply(email, Optional.empty())),
				address,
				email.subject(),
				isThreadable ? ThreadingHeaders.of(email) : ThreadingHeaders.none());
		}
		catch (RuntimeException e) {
			Log.warnf(e, "Couldn't send the processing-problem reply to %s", address);
		}
	}
}
