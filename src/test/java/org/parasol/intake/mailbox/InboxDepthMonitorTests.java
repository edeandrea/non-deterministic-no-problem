package org.parasol.intake.mailbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.parasol.intake.config.IntakeConfig;
import org.parasol.intake.observability.IntakeActivities;
import org.parasol.intake.observability.IntakeActivity;
import org.parasol.intake.observability.IntakeActivity.InboxDepthMeasured;

import static org.assertj.core.api.Assertions.assertThat;

class InboxDepthMonitorTests {
	private final IntakeConfig config = mock(IntakeConfig.class);
	private final ClaimsMailbox mailbox = mock(ClaimsMailbox.class);
	private final IntakeActivities activities = mock(IntakeActivities.class);
	private final InboxDepthMonitor monitor = new InboxDepthMonitor(this.config, this.mailbox, this.activities);

	@Test
	void publishesTheInboxDepth() {
		when(this.config.enabled()).thenReturn(true);
		when(this.mailbox.inboxDepth()).thenReturn(3);

		this.monitor.measure();

		var published = ArgumentCaptor.forClass(IntakeActivity.class);
		verify(this.activities).publish(published.capture());
		assertThat(published.getValue()).isEqualTo(new InboxDepthMeasured(3));
	}

	@Test
	void aFailedMeasurementIsSkipped() {
		when(this.config.enabled()).thenReturn(true);
		when(this.mailbox.inboxDepth()).thenThrow(new MailboxException("The mailbox is down", new IllegalStateException()));

		this.monitor.measure();

		verify(this.activities, never()).publish(any());
	}

	@Test
	void nothingIsMeasuredWhileTheIntakeIsOff() {
		when(this.config.enabled()).thenReturn(false);

		this.monitor.measure();

		verify(this.mailbox, never()).inboxDepth();
		verify(this.activities, never()).publish(any());
	}
}
