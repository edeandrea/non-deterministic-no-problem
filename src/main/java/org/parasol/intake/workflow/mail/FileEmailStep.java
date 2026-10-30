package org.parasol.intake.workflow.mail;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import org.parasol.intake.mailbox.ClaimsMailbox;
import org.parasol.intake.mailbox.MailFolder;
import org.parasol.intake.model.IntakeOutcome;
import org.parasol.intake.observability.EmailOutcome;
import org.parasol.intake.observability.IntakeActivities;
import org.parasol.intake.observability.IntakeActivity.EmailHandled;
import org.parasol.intake.workflow.IntakeState;

import io.quarkus.logging.Log;

/**
 * The workflow's {@code file} step: moves the email from the processing folder to the processed one, also when the run is
 * about to wait for review. Every path that ends with the email handled passes through here, so this is where it is counted.
 */
@ApplicationScoped
public class FileEmailStep {
	private final ClaimsMailbox mailbox;
	private final IntakeActivities activities;

	FileEmailStep(ClaimsMailbox mailbox, IntakeActivities activities) {
		this.mailbox = mailbox;
		this.activities = activities;
	}

	public IntakeState file(IntakeState state) {
		var isMoved = this.mailbox.move(state.messageId(), MailFolder.PROCESSING, MailFolder.PROCESSED);

		if (!isMoved) {
			Log.warnf("%s wasn't in the processing folder to file", state.messageId());
		}

		this.activities.publish(new EmailHandled(outcomeOf(state), Optional.ofNullable(state.receivedAt()).map(received -> Duration.between(Instant.parse(received), Instant.now()))));

		return state;
	}

	static EmailOutcome outcomeOf(IntakeState state) {
		return switch (state.disposition()) {
			case WRONG_SENDER -> EmailOutcome.NO_MATCHING_CLAIM;
			case WHICH_CLAIM -> EmailOutcome.NEEDS_CLARIFICATION;
			case POLICY_REJECTED -> EmailOutcome.POLICY_REJECTED;
			case CONTINUE, REQUEUE -> switch (state.outcome()) {
				case IntakeOutcome.NewClaim _ -> EmailOutcome.NEW_CLAIM;
				case IntakeOutcome.PendingClaimUpdate _ -> EmailOutcome.CLAIM_UPDATE;
				case IntakeOutcome.StatusReply _ -> EmailOutcome.STATUS_QUESTION;
				case IntakeOutcome.NotAClaim _ -> EmailOutcome.NOT_A_CLAIM;
				case IntakeOutcome.NoMatchingClaim _ -> EmailOutcome.NO_MATCHING_CLAIM;
			};
		};
	}
}
