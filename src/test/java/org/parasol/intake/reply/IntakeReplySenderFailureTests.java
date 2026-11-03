package org.parasol.intake.reply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.parasol.intake.config.IntakeConfig;
import org.parasol.intake.observability.FailureStage;
import org.parasol.intake.observability.IntakeActivityEvents;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.smallrye.mutiny.TimeoutException;
import io.smallrye.mutiny.Uni;

/**
 * A reply that can't be sent is a {@link ReplyFailedException} whatever the mailer threw, so its failure is a {@code reply} one.
 */
class IntakeReplySenderFailureTests {
	@Test
	void aMailerFailureIsAReplyFailure() {
		var failure = new IllegalStateException("The SMTP server refused the mail");

		assertThatThrownBy(() -> send(Uni.createFrom().failure(failure), Duration.ofSeconds(5)))
			.isInstanceOf(ReplyFailedException.class)
			.hasCause(failure)
			.satisfies(thrown -> assertThat(FailureStage.of(thrown)).isEqualTo(FailureStage.REPLY));
	}

	@Test
	void aFailedSendRecordsNothing() {
		var recorder = mock(ReplyRecorder.class);

		assertThatThrownBy(() -> send(Uni.createFrom().failure(new IllegalStateException("The SMTP server refused the mail")), Duration.ofSeconds(5), recorder))
			.isInstanceOf(ReplyFailedException.class);

		verify(recorder, never()).record(anyString(), any(SentReply.class));
	}

	// The reply has gone out by then, and failing the step would answer the customer twice
	@Test
	void aRecordingFailureDoesNotFailTheSend() {
		var recorder = mock(ReplyRecorder.class);

		doThrow(new IllegalStateException("The database is down")).when(recorder).record(anyString(), any(SentReply.class));

		assertThatCode(() -> send(Uni.createFrom().voidItem(), Duration.ofSeconds(5), recorder))
			.doesNotThrowAnyException();

		verify(recorder).record(anyString(), any(SentReply.class));
	}

	@Test
	void aStalledMailerTimesOutAsAReplyFailure() {
		assertThatThrownBy(() -> send(Uni.createFrom().nothing(), Duration.ofMillis(100)))
			.isInstanceOf(ReplyFailedException.class)
			.hasCauseInstanceOf(TimeoutException.class)
			.satisfies(thrown -> assertThat(FailureStage.of(thrown)).isEqualTo(FailureStage.REPLY));
	}

	private static void send(Uni<Void> send, Duration timeout) {
		send(send, timeout, mock(ReplyRecorder.class));
	}

	private static void send(Uni<Void> send, Duration timeout, ReplyRecorder recorder) {
		var config = mock(IntakeConfig.class);
		var mailer = mock(ReactiveMailer.class);

		when(config.address()).thenReturn("claims@parasol.com");
		when(mailer.send(any(Mail.class))).thenReturn(send);

		new IntakeReplySender(config, mailer, timeout, IntakeActivityEvents.into(new SimpleMeterRegistry()), recorder)
			.sendTextReply("jane.doe@example.com", "Car accident", "Thanks", "CLM01000000", ThreadingHeaders.none());
	}
}
