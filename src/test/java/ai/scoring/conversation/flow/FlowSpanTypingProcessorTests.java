package ai.scoring.conversation.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes;
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GenAiOperationNameIncubatingValues;

@QuarkusTest
class FlowSpanTypingProcessorTests {
	@Inject
	Tracer tracer;

	@Test
	void aWorkflowGeneratedFromAnAgenticInterfaceIsAnAgent() {
		assertThat(operationNameOf(startWorkflowSpan("org-parasol-intake-agent", "claims-mailbox-agent")))
			.contains(GenAiOperationNameIncubatingValues.INVOKE_AGENT);
		assertThat(operationNameOf(startWorkflowSpan("org-parasol-intake-agent-extraction", "claim-extraction-workflow")))
			.contains(GenAiOperationNameIncubatingValues.INVOKE_AGENT);
	}

	@Test
	void anAgenticWorkflowSpanCarriesTheAgentName() {
		var span = startWorkflowSpan("org-parasol-intake-agent", "claims-mailbox-agent");

		assertThat(((ReadableSpan) span).getAttribute(GenAiIncubatingAttributes.GEN_AI_AGENT_NAME)).isEqualTo("claims-mailbox-agent");
	}

	@Test
	void aPlainWorkflowIsLeftAlone() {
		assertThat(operationNameOf(startWorkflowSpan("org-parasol-intake-workflow", "claim-intake"))).isEmpty();
	}

	@Test
	void aWorkflowWhoseInterfaceIsNotAgenticIsLeftAlone() {
		assertThat(operationNameOf(startWorkflowSpan("org-parasol-claim-model", "claim"))).isEmpty();
		assertThat(operationNameOf(startWorkflowSpan("org-parasol-intake-agent", "no-such-agent"))).isEmpty();
	}

	@Test
	void otherSpansAreLeftAlone() {
		var span = this.tracer.spanBuilder("workflow.create claims-mailbox-agent")
			.setAttribute("flow.workflow.namespace", "org-parasol-intake-agent")
			.setAttribute("flow.workflow.name", "claims-mailbox-agent")
			.startSpan();
		span.end();

		assertThat(operationNameOf(span)).isEmpty();
	}

	private Span startWorkflowSpan(String namespace, String name) {
		var span = this.tracer.spanBuilder("workflow.execute " + name)
			.setAttribute("flow.workflow.namespace", namespace)
			.setAttribute("flow.workflow.name", name)
			.startSpan();
		span.end();
		return span;
	}

	private static Optional<String> operationNameOf(Span span) {
		return Optional.ofNullable(((ReadableSpan) span).getAttribute(GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME));
	}
}
