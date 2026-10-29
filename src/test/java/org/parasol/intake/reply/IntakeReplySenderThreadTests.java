package org.parasol.intake.reply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.parasol.intake.config.IntakeConfig;
import org.parasol.intake.observability.IntakeActivityEvents;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.smallrye.mutiny.Uni;

/**
 * Where {@link IntakeReplySender} subscribes to the send. In dev mode a thread that doesn't carry the caller's context class
 * loader can't read config while rendering a template ({@code ServiceConfigurationError: ... QuarkusConfigFactory not a
 * subtype of SmallRyeConfigFactory}), which a {@code @QuarkusTest}'s flat class loading never shows, so this checks the
 * cause: the thread that sends has the caller's loader, and isn't the caller's thread.
 */
class IntakeReplySenderThreadTests {
	private final ClassLoader original = Thread.currentThread().getContextClassLoader();

	@AfterEach
	void restoreClassLoader() {
		Thread.currentThread().setContextClassLoader(this.original);
	}

	@Test
	void theSendRunsOnAnotherThreadWithTheCallersContextClassLoader() {
		var callersLoader = new ClassLoader(this.original) {
		};
		var sendingLoader = new AtomicReference<ClassLoader>();
		var sendingThread = new AtomicReference<Thread>();
		var config = mock(IntakeConfig.class);
		var mailer = mock(ReactiveMailer.class);

		when(config.address()).thenReturn("claims@parasol.com");
		when(mailer.send(any(Mail.class))).thenReturn(Uni.createFrom().deferred(() -> {
			sendingLoader.set(Thread.currentThread().getContextClassLoader());
			sendingThread.set(Thread.currentThread());
			return Uni.createFrom().voidItem();
		}));

		Thread.currentThread().setContextClassLoader(callersLoader);

		new IntakeReplySender(config, mailer, Duration.ofSeconds(5), IntakeActivityEvents.into(new SimpleMeterRegistry()))
			.sendTextReply("jane.doe@example.com", "Car accident", "Thanks", "CLM01000000", ThreadingHeaders.none());

		assertThat(sendingLoader).hasValue(callersLoader);
		assertThat(sendingThread.get())
			.isNotSameAs(Thread.currentThread())
			.satisfies(thread -> assertThat(thread.isVirtual()).isTrue());
	}
}
