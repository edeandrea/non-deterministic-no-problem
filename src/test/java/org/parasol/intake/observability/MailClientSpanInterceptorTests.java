package org.parasol.intake.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.interceptor.InvocationContext;

import org.junit.jupiter.api.Test;
import org.parasol.intake.config.IntakeConfig;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

/**
 * A failed call ends its span in error whatever it threw, and the span names the exception's class but never its message,
 * which can name the mailbox's address.
 */
class MailClientSpanInterceptorTests {
	private static final String MESSAGE_WITH_AN_ADDRESS = "Couldn't access the INBOX folder of claims@parasol.com";

	private final List<SpanData> spans = new CopyOnWriteArrayList<>();
	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
	private final MailClientSpanInterceptor interceptor = interceptor(this.spans, this.registry);

	@Test
	void aCheckedExceptionEndsTheSpanInError() {
		assertThatThrownBy(() -> this.interceptor.span(failing(new IOException(MESSAGE_WITH_AN_ADDRESS))))
			.isInstanceOf(IOException.class);

		assertThat(this.spans)
			.singleElement()
			.satisfies(span -> assertThat(span.getName()).isEqualTo("imap list"))
			.satisfies(span -> assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR))
			.satisfies(span -> assertThat(span.getEvents()).singleElement()
				.satisfies(event -> assertThat(event.getAttributes().asMap().values()).containsExactly(IOException.class.getName())))
			.satisfies(span -> assertThat(span.getAttributes().asMap().values()).noneMatch(value -> value.toString().contains("@")));
	}

	@Test
	void aRuntimeExceptionEndsTheSpanInError() {
		assertThatThrownBy(() -> this.interceptor.span(failing(new IllegalStateException(MESSAGE_WITH_AN_ADDRESS))))
			.isInstanceOf(IllegalStateException.class);

		assertThat(this.spans)
			.singleElement()
			.satisfies(span -> assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR));
	}

	@Test
	void aSuccessfulCallLeavesTheStatusUnset() throws Exception {
		var context = failing(null);

		assertThat(this.interceptor.span(context)).isEqualTo("done");
		assertThat(this.spans)
			.singleElement()
			.satisfies(span -> assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET))
			.satisfies(span -> assertThat(span.getEvents()).isEmpty());
	}

	@Test
	void aSuccessfulCallIsTimedAsASuccess() throws Exception {
		this.interceptor.span(failing(null));

		assertThat(this.registry.find("claim.intake.mail.client.duration").tags("protocol", "imap", "operation", "list", "outcome", "success").timer())
			.isNotNull()
			.satisfies(timer -> assertThat(timer.count()).isEqualTo(1));
		assertThat(this.registry.find("claim.intake.mail.client.duration").tag("outcome", "error").timer()).isNull();
	}

	@Test
	void aFailedCallIsTimedAsAnError() {
		assertThatThrownBy(() -> this.interceptor.span(failing(new IOException(MESSAGE_WITH_AN_ADDRESS))))
			.isInstanceOf(IOException.class);

		assertThat(this.registry.find("claim.intake.mail.client.duration").tags("protocol", "imap", "operation", "list", "outcome", "error").timer())
			.isNotNull()
			.satisfies(timer -> assertThat(timer.count()).isEqualTo(1));
		assertThat(this.registry.find("claim.intake.mail.client.duration").tag("outcome", "success").timer()).isNull();
	}

	private static InvocationContext failing(Exception failure) throws Exception {
		var context = mock(InvocationContext.class);

		when(context.getMethod()).thenReturn(method());
		when(context.getParameters()).thenReturn(new Object[] { "INBOX" });

		if (failure == null) {
			when(context.proceed()).thenReturn("done");
		}
		else {
			when(context.proceed()).thenThrow(failure);
		}

		return context;
	}

	private static Method method() throws NoSuchMethodException {
		return Mailbox.class.getDeclaredMethod("list", String.class);
	}

	private static MailClientSpanInterceptor interceptor(List<SpanData> spans, SimpleMeterRegistry registry) {
		var imap = mock(IntakeConfig.Imap.class);
		var config = mock(IntakeConfig.class);

		when(imap.host()).thenReturn("localhost");
		when(imap.port()).thenReturn(3143);
		when(config.imap()).thenReturn(imap);

		var tracer = SdkTracerProvider.builder()
			.addSpanProcessor(SimpleSpanProcessor.create(new CollectingExporter(spans)))
			.build()
			.get("test");

		return new MailClientSpanInterceptor(tracer, config, IntakeActivityEvents.into(registry), "localhost", Optional.of(3025));
	}

	private static final class Mailbox {
		@MailClientSpan(protocol = MailClientSpan.Protocol.IMAP, operation = "list")
		void list(@SpanAttribute("folder") String folder) {
		}
	}

	private record CollectingExporter(List<SpanData> spans) implements SpanExporter {
		@Override
		public CompletableResultCode export(Collection<SpanData> exported) {
			this.spans.addAll(exported);

			return CompletableResultCode.ofSuccess();
		}

		@Override
		public CompletableResultCode flush() {
			return CompletableResultCode.ofSuccess();
		}

		@Override
		public CompletableResultCode shutdown() {
			return CompletableResultCode.ofSuccess();
		}
	}
}
