package org.parasol.intake.failure;

import jakarta.enterprise.context.ApplicationScoped;

import org.parasol.intake.mailbox.ClaimsMailbox;
import org.parasol.intake.mailbox.InboundEmail;
import org.parasol.intake.mailbox.MailFolder;
import org.parasol.intake.observability.EmailOutcome;
import org.parasol.intake.observability.FailureOutcome;
import org.parasol.intake.observability.FailureStage;
import org.parasol.intake.observability.IntakeActivities;
import org.parasol.intake.observability.IntakeActivity.EmailHandled;
import org.parasol.intake.observability.IntakeActivity.IntakeFailed;

import io.quarkus.logging.Log;

/**
 * The failure path: the email goes to the failed folder (if it's still in the processing folder), the failure is logged at
 * ERROR with its {@code Message-ID}, and the sender is told, best-effort. Nothing here throws.
 */
@ApplicationScoped
public class IntakeFailureHandler {
	private final ClaimsMailbox mailbox;
	private final ProcessingProblemReplySender processingProblem;
	private final IntakeActivities activities;

	IntakeFailureHandler(ClaimsMailbox mailbox, ProcessingProblemReplySender processingProblem, IntakeActivities activities) {
		this.mailbox = mailbox;
		this.processingProblem = processingProblem;
		this.activities = activities;
	}

	/**
	 * Handles an email whose run failed, before or after the review wait.
	 *
	 * @param messageId The {@code Message-ID} of the email
	 * @param cause What failed
	 */
	public void handle(String messageId, Throwable cause) {
		Log.errorf(cause, "Processing %s failed", messageId);
		this.activities.publish(new EmailHandled(EmailOutcome.FAILED));
		this.activities.publish(new IntakeFailed(FailureStage.of(cause), cause.getClass().getSimpleName(), FailureOutcome.FATAL));

		try {
			var inProcessing = this.mailbox.find(MailFolder.PROCESSING, messageId);

			// An email already filed (the run failed after its review wait) isn't moved, but its sender is still told
			var email = inProcessing.or(() -> this.mailbox.find(MailFolder.PROCESSED, messageId));
			inProcessing.ifPresent(_ -> this.mailbox.move(messageId, MailFolder.PROCESSING, MailFolder.FAILED));
			email.ifPresent(this.processingProblem::send);
		}
		catch (RuntimeException e) {
			Log.errorf(e, "Couldn't finish the failure handling of %s", messageId);
		}
	}

	/**
	 * Handles an email that has no {@code Message-ID}: no run is started, because steps find their email by that id.
	 *
	 * @param email The email
	 */
	public void handleWithoutMessageId(InboundEmail email) {
		this.activities.publish(new EmailHandled(EmailOutcome.FAILED));
		this.activities.publish(new IntakeFailed(FailureStage.FETCH, IllegalArgumentException.class.getSimpleName(), FailureOutcome.FATAL));
		Log.warnf("An email from %s has no Message-ID, so it can't be processed: filing it as failed", email.fromAddress().orElse("an unknown sender"));

		try {
			this.mailbox.moveWithoutMessageId(MailFolder.PROCESSING, MailFolder.FAILED);
			this.processingProblem.send(email);
		}
		catch (RuntimeException e) {
			Log.errorf(e, "Couldn't finish the failure handling of an email without a Message-ID");
		}
	}

	/**
	 * Files an email that was handled without a run: an auto-reply, self-sent mail or a duplicate.
	 *
	 * @param email The email
	 */
	public void fileWithoutRun(InboundEmail email) {
		var isMoved = email.messageId()
			.map(id -> this.mailbox.move(id, MailFolder.PROCESSING, MailFolder.PROCESSED))
			.orElseGet(() -> this.mailbox.moveWithoutMessageId(MailFolder.PROCESSING, MailFolder.PROCESSED));

		Log.debugf("Filed %s without a run: %s", email.messageId().orElse("an email without a Message-ID"), isMoved);
	}
}