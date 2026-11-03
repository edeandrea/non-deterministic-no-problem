package ai.scoring.langfuse.otel;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes;

@QuarkusTest
class LangfuseFlowSpanTypingProcessorTests {
	@Inject
	Tracer tracer;

	@Test
	void taskSpansAreChains() {
		var task = (ReadableSpan) startSpan("task.execute initialize");

		assertThat(task.getAttribute(GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME)).isNull();
		assertThat(task.getAttribute(LangfuseFlowSpanTypingProcessor.LANGFUSE_OBSERVATION_TYPE)).isEqualTo(LangfuseFlowSpanTypingProcessor.CHAIN);
	}

	@Test
	void otherSpansAreLeftAlone() {
		var other = (ReadableSpan) startSpan("workflow.execute claim-intake");

		assertThat(other.getAttribute(LangfuseFlowSpanTypingProcessor.LANGFUSE_OBSERVATION_TYPE)).isNull();
	}

	private io.opentelemetry.api.trace.Span startSpan(String name) {
		var span = this.tracer.spanBuilder(name).startSpan();
		span.end();
		return span;
	}
}
