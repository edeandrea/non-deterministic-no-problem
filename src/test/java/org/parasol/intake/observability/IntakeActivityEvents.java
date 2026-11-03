package org.parasol.intake.observability;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import jakarta.enterprise.event.Event;

import org.parasol.intake.observability.IntakeActivity.ClaimCompleted;
import org.parasol.intake.observability.IntakeActivity.ClaimStatusChanged;
import org.parasol.intake.observability.IntakeActivity.EmailAccepted;
import org.parasol.intake.observability.IntakeActivity.EmailClassified;
import org.parasol.intake.observability.IntakeActivity.EmailHandled;
import org.parasol.intake.observability.IntakeActivity.EmailMatched;
import org.parasol.intake.observability.IntakeActivity.InboxDepthMeasured;
import org.parasol.intake.observability.IntakeActivity.IntakeFailed;
import org.parasol.intake.observability.IntakeActivity.MailClientCompleted;
import org.parasol.intake.observability.IntakeActivity.MailboxLockAcquired;
import org.parasol.intake.observability.IntakeActivity.NewClaimExtracted;
import org.parasol.intake.observability.IntakeActivity.ReplySent;
import org.parasol.intake.observability.IntakeActivity.ReviewDecisionApplied;
import org.parasol.intake.observability.IntakeActivity.WatcherConnectionChanged;
import org.parasol.intake.observability.IntakeActivity.WatcherReconnecting;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * The {@link IntakeActivities} for a class a test builds by hand: it hands each activity to {@link IntakeMetrics} recording into
 * the given registry, as CDI would.
 */
public final class IntakeActivityEvents {
	private IntakeActivityEvents() {
	}

	@SuppressWarnings("unchecked")
	public static IntakeActivities into(MeterRegistry registry) {
		var metrics = new IntakeMetrics(registry);
		var event = (Event<IntakeActivity>) mock(Event.class);

		doAnswer(invocation -> {
			switch (invocation.<IntakeActivity>getArgument(0)) {
				case EmailHandled handled -> metrics.onEmailHandled(handled);
				case EmailAccepted accepted -> metrics.onEmailAccepted(accepted);
				case EmailMatched matched -> metrics.onEmailMatched(matched);
				case EmailClassified classified -> metrics.onEmailClassified(classified);
				case NewClaimExtracted extracted -> metrics.onNewClaimExtracted(extracted);
				case ClaimCompleted completed -> metrics.onClaimCompleted(completed);
				case MailClientCompleted completed -> metrics.onMailClientCompleted(completed);
				case MailboxLockAcquired acquired -> metrics.onMailboxLockAcquired(acquired);
				case InboxDepthMeasured measured -> metrics.onInboxDepthMeasured(measured);
				case ClaimStatusChanged changed -> metrics.onClaimStatusChanged(changed);
				case ReplySent sent -> metrics.onReplySent(sent);
				case ReviewDecisionApplied applied -> metrics.onReviewDecisionApplied(applied);
				case IntakeFailed failed -> metrics.onFailed(failed);
				case WatcherConnectionChanged changed -> metrics.onWatcherConnectionChanged(changed);
				case WatcherReconnecting reconnecting -> metrics.onWatcherReconnecting(reconnecting);
			}

			return null;
		})
			.when(event)
			.fire(any());

		return new IntakeActivities(event);
	}
}
