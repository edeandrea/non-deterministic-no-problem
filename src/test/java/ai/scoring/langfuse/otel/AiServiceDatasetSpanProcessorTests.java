package ai.scoring.langfuse.otel;

import static ai.scoring.langfuse.otel.AiServiceAttributes.AI_SERVICE_CLASS;
import static ai.scoring.langfuse.otel.AiServiceAttributes.AI_SERVICE_METHOD;
import static ai.scoring.langfuse.otel.AiServiceAttributes.DATASET_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;

class AiServiceDatasetSpanProcessorTests {
	private static final String SERVICE_CLASS = "AiServiceClassName";
	private static final String METHOD_NAME = "methodName";
	private static final String SPAN_NAME = "%s%s.%s".formatted(AiServiceAttributes.AI_SERVICES_PREFIX, SERVICE_CLASS, METHOD_NAME);
	private static final String PACKAGE_QUALIFIED_SPAN_NAME = "%sorg.acme.%s.%s".formatted(AiServiceAttributes.AI_SERVICES_PREFIX, SERVICE_CLASS, METHOD_NAME);

	private AiServiceDatasetSpanProcessor processor;

	@BeforeEach
	void setUp() {
		this.processor = new AiServiceDatasetSpanProcessor();
	}

	@Test
	void rootAiServiceSpanEnrichmentWithSimpleName() {
		var span = mock(ReadWriteSpan.class);
		when(span.getName())
			.thenReturn(SPAN_NAME);

		this.processor.onStart(Context.root(), span);

		verify(span).setAttribute(DATASET_NAME, SPAN_NAME);
		verify(span).setAttribute(AI_SERVICE_CLASS, SERVICE_CLASS);
		verify(span).setAttribute(AI_SERVICE_METHOD, METHOD_NAME);
	}

	@Test
	void rootAiServiceSpanEnrichmentWithPackageQualifiedName() {
		var span = mock(ReadWriteSpan.class);
		when(span.getName())
			.thenReturn(PACKAGE_QUALIFIED_SPAN_NAME);

		this.processor.onStart(Context.root(), span);

		verify(span).setAttribute(DATASET_NAME, PACKAGE_QUALIFIED_SPAN_NAME);
		verify(span).setAttribute(AI_SERVICE_CLASS, SERVICE_CLASS);
		verify(span).setAttribute(AI_SERVICE_METHOD, METHOD_NAME);
	}

	@Test
	void childSpanCascadesFromParent() {
		// The processor reads attributes off Span.fromContext(parentContext), which must be a ReadableSpan.
		// ReadWriteSpan extends both, so a mock of it doubles as a parent the processor can read from.
		var parentSpan = mock(ReadWriteSpan.class);
		// storeInContext is a default method on Span; let it run for real so Context.with(parentSpan) works
		when(parentSpan.storeInContext(any()))
			.thenCallRealMethod();
		when(parentSpan.getAttribute(DATASET_NAME))
			.thenReturn(SPAN_NAME);
		when(parentSpan.getAttribute(AI_SERVICE_CLASS))
			.thenReturn(SERVICE_CLASS);
		when(parentSpan.getAttribute(AI_SERVICE_METHOD))
			.thenReturn(METHOD_NAME);

		var parentContext = Context.root().with(parentSpan);

		var childSpan = mock(ReadWriteSpan.class);
		when(childSpan.getName())
			.thenReturn("completion gpt-5-mini");

		this.processor.onStart(parentContext, childSpan);

		verify(childSpan).setAttribute(DATASET_NAME, SPAN_NAME);
		verify(childSpan).setAttribute(AI_SERVICE_CLASS, SERVICE_CLASS);
		verify(childSpan).setAttribute(AI_SERVICE_METHOD, METHOD_NAME);
	}

	@Test
	void unrelatedSpanWithoutParentAttributesIsNotEnriched() {
		// Context.root() has no parent span at all, so neither branch of onStart should touch this span
		var span = mock(ReadWriteSpan.class);
		when(span.getName())
			.thenReturn("HTTP GET /api/db/claims");

		this.processor.onStart(Context.root(), span);

		verify(span, never()).setAttribute(DATASET_NAME, SPAN_NAME);
		verify(span, never()).setAttribute(AI_SERVICE_CLASS, SERVICE_CLASS);
		verify(span, never()).setAttribute(AI_SERVICE_METHOD, METHOD_NAME);
	}

	@Test
	void processorLifecycleCapabilities() {
		assertThat(this.processor.isStartRequired())
			.isTrue();

		assertThat(this.processor.isEndRequired())
			.isFalse();

		assertThat(this.processor.shutdown().isSuccess())
			.isTrue();

		assertThat(this.processor.forceFlush().isSuccess())
			.isTrue();

		var readableSpan = mock(ReadableSpan.class);
		this.processor.onEnd(readableSpan);
		verifyNoInteractions(readableSpan);
	}
}
