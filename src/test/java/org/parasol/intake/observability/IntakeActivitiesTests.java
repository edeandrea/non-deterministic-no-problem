package org.parasol.intake.observability;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import jakarta.enterprise.event.Event;

import org.junit.jupiter.api.Test;
import org.parasol.intake.observability.IntakeActivity.EmailHandled;

class IntakeActivitiesTests {
	private static final EmailHandled ACTIVITY = new EmailHandled(EmailOutcome.NEW_CLAIM);

	@SuppressWarnings("unchecked")
	private final Event<IntakeActivity> event = mock(Event.class);

	private final IntakeActivities activities = new IntakeActivities(this.event);

	@Test
	void anActivityIsHandedToItsObservers() {
		this.activities.publish(ACTIVITY);

		verify(this.event).fire(ACTIVITY);
	}

	@Test
	void anObserverThatThrowsNeverReachesThePublisher() {
		doThrow(new IllegalStateException("The meter registry is broken")).when(this.event).fire(ACTIVITY);

		assertThatCode(() -> this.activities.publish(ACTIVITY)).doesNotThrowAnyException();
	}
}
