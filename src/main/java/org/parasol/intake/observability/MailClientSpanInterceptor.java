package org.parasol.intake.observability;

import java.time.Duration;
import java.util.Optional;
import java.util.stream.IntStream;

import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.parasol.intake.config.IntakeConfig;
import org.parasol.intake.observability.IntakeActivity.MailClientCompleted;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;

/**
 * Wraps a {@link MailClientSpan} method in a {@code CLIENT} span, with an attribute for each parameter marked
 * {@link SpanAttribute}. A span is a child of whatever is current, so it carries the
 * run's conversation id like every other span, and whatever the method starts on another thread with
 * {@code Context.current().wrap(...)} stays inside it.
 * <p>
 *   The attributes name the server and what a parameter says it is (the folder), never an address, a subject, a body or a {@code Message-ID}.
 * </p>
 */
@MailClientSpan(protocol = MailClientSpan.Protocol.IMAP, operation = "")
@Interceptor
@Priority(Interceptor.Priority.LIBRARY_BEFORE)
class MailClientSpanInterceptor {
	private static final AttributeKey<String> MESSAGING_SYSTEM = AttributeKey.stringKey("messaging.system");
	private static final AttributeKey<String> SERVER_ADDRESS = AttributeKey.stringKey("server.address");
	private static final AttributeKey<Long> SERVER_PORT = AttributeKey.longKey("server.port");
	private static final AttributeKey<String> EXCEPTION_TYPE = AttributeKey.stringKey("exception.type");
	private static final int DEFAULT_SMTP_PORT = 25;

	private final Tracer tracer;
	private final IntakeConfig config;
	private final IntakeActivities activities;
	private final String smtpHost;
	private final int smtpPort;

	MailClientSpanInterceptor(
		Tracer tracer,
		IntakeConfig config,
		IntakeActivities activities,
		@ConfigProperty(name = "quarkus.mailer.host") String smtpHost,
		@ConfigProperty(name = "quarkus.mailer.port") Optional<Integer> smtpPort
	) {
		this.tracer = tracer;
		this.config = config;
		this.activities = activities;
		this.smtpHost = smtpHost;
		this.smtpPort = smtpPort.orElse(DEFAULT_SMTP_PORT);
	}

	@AroundInvoke
	Object span(InvocationContext context) throws Exception {
		var binding = Optional.ofNullable(context.getMethod().getAnnotation(MailClientSpan.class))
			.orElseGet(() -> context.getMethod()
				.getDeclaringClass()
				.getAnnotation(MailClientSpan.class));
		var imap = binding.protocol() == MailClientSpan.Protocol.IMAP;
		var protocol = binding.protocol().name().toLowerCase();
		var builder = this.tracer.spanBuilder(protocol + " " + binding.operation())
			.setSpanKind(SpanKind.CLIENT)
			.setAttribute(MESSAGING_SYSTEM, protocol)
			.setAttribute(SERVER_ADDRESS, imap ? this.config.imap().host() : this.smtpHost)
			.setAttribute(SERVER_PORT, (long) (imap ? this.config.imap().port() : this.smtpPort));

		var parameters = context.getMethod().getParameters();

		IntStream.range(0, parameters.length)
			.forEach(i -> Optional.ofNullable(parameters[i].getAnnotation(SpanAttribute.class))
				.filter(attribute -> !attribute.value().isEmpty())
				.ifPresent(attribute -> builder.setAttribute(attribute.value(), String.valueOf(context.getParameters()[i]))));

		var span = builder.startSpan();
		var startedAt = System.nanoTime();
		var failed = false;

		try (var _ = span.makeCurrent()) {
			return context.proceed();
		}
		catch (Exception e) {
			failed = true;
			// Not recordException: a mailbox exception's message names the mailbox's address
			span.addEvent("exception", Attributes.of(EXCEPTION_TYPE, e.getClass().getName()))
			    .setStatus(StatusCode.ERROR, e.getClass().getSimpleName());
			throw e;
		}
		finally {
			span.end();
			this.activities.publish(new MailClientCompleted(binding.protocol(), binding.operation(), failed, Duration.ofNanos(System.nanoTime() - startedAt)));
		}
	}
}
