package org.parasol.intake.failure;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

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
	 * <p>
	 *   <b>Where the email is decides what its sender hears.</b> The flow files an email only after it has replied about
	 *   it — every branch is {@code reply → file} — so an email that has left the processing folder has already been
	 *   answered, and telling its sender now that we couldn't process it would contradict the reply they already have. It
	 *   was also counted as handled when it was filed. Such a run's fault is recorded for an operator (see {@code outcome})
	 *   and the customer hears nothing.
	 * </p>
	 *
	 * @param messageId The {@code Message-ID} of the email
	 * @param cause What failed
	 * @param outcome Whether anything picked the claim up: {@link FailureOutcome#RECOVERED} when another run took its
	 * review over, otherwise {@link FailureOutcome#FATAL}
	 */
	public void handle(String messageId, Throwable cause, FailureOutcome outcome) {
		Log.errorf(cause, "Processing %s failed", messageId);
		this.activities.publish(new IntakeFailed(FailureStage.of(cause), cause.getClass().getSimpleName(), outcome));

		unansweredEmail(messageId).ifPresent(email -> fileAsFailed(messageId, email));
	}

	// Nothing on the failure path may throw: a mailbox that can't be read is logged and the email left alone. Each case logs
	// only what it actually knows — an unreadable mailbox is not evidence that the email was already answered
	private Optional<InboundEmail> unansweredEmail(String messageId) {
		var found = Optional.<InboundEmail>empty();

		try {
			found = this.mailbox.find(MailFolder.PROCESSING, messageId);

			if (found.isEmpty()) {
				Log.warnf("The run for %s faulted after its email was filed and answered, so its sender is told nothing", messageId);
			}
		}
		catch (RuntimeException e) {
			Log.errorf(e, "Couldn't look for %s in the processing folder, so its sender is told nothing", messageId);
		}

		return found;
	}

	// Counted even when the move or the reply fails: the email is finished either way, and its latency is real, because it
	// never left the processing folder
	private void fileAsFailed(String messageId, InboundEmail email) {
		try {
			this.mailbox.move(messageId, MailFolder.PROCESSING, MailFolder.FAILED);
			this.processingProblem.send(email);
		}
		catch (RuntimeException e) {
			Log.errorf(e, "Couldn't finish the failure handling of %s", messageId);
		}
		finally {
			this.activities.publish(new EmailHandled(EmailOutcome.FAILED, Optional.of(latencyOf(email))));
		}
	}

	/**
	 * Handles an email that has no {@code Message-ID}: no run is started, because steps find their email by that id.
	 *
	 * @param email The email
	 */
	public void handleWithoutMessageId(InboundEmail email) {
		this.activities.publish(new EmailHandled(EmailOutcome.FAILED, Optional.of(latencyOf(email))));
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

	private static Duration latencyOf(InboundEmail email) {
		return Duration.between(email.receivedDate(), Instant.now());
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