package ai.scoring.conversation.flow;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import jakarta.inject.Singleton;

import dev.langchain4j.agentic.declarative.ConditionalAgent;
import dev.langchain4j.agentic.declarative.LoopAgent;
import dev.langchain4j.agentic.declarative.ParallelAgent;
import dev.langchain4j.agentic.declarative.ParallelMapperAgent;
import dev.langchain4j.agentic.declarative.PlannerAgent;
import dev.langchain4j.agentic.declarative.SequenceAgent;
import dev.langchain4j.agentic.declarative.SupervisorAgent;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes;
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GenAiOperationNameIncubatingValues;

/**
 * <b>WORKAROUND, to be deleted</b> once quarkiverse/quarkus-flow#1122 is released. Flow should type its own agentic workflow
 * spans; until it does, this does it from the outside.
 * <p>
 * It types the {@code workflow.execute} span of a Flow workflow generated for a LangChain4j agentic system as an agent
 * ({@code gen_ai.operation.name=invoke_agent}, and {@code gen_ai.agent.name} from {@code flow.workflow.name}). Plain workflows,
 * and every other span, are left alone.
 * <p>
 * <b>Why it is needed.</b> Flow's spans carry no {@code gen_ai.*} attribute. A backend that only keeps spans having one (plus
 * their ancestors in the same export batch) can drop a long-lived workflow span, and one that types spans from
 * {@code gen_ai.operation.name} shows it as a generic span.
 * <p>
 * <b>Why it is ugly.</b> A span can't say whether its workflow is agentic. The workflow definition's metadata
 * ({@code io.quarkiverse.flow.invoker.kind}) would, but Flow sets it in dev mode only, and a listener that reads it couldn't
 * work in tests or production. So this relies on two things Flow doesn't promise:
 * <ul>
 *   <li><b>Flow's naming.</b> A generated workflow is named after its agent interface in kebab case:
 *   {@code flow.workflow.namespace} is the package with dashes for dots and {@code flow.workflow.name} the simple name
 *   ({@code org-parasol-intake-agent} and {@code claims-mailbox-agent} are {@code org.parasol.intake.agent.ClaimsMailboxAgent}).
 *   A different naming scheme, or an acronym in an agent's name, silently stops the typing.</li>
 *   <li><b>Reflection.</b> The interface is loaded and looked up for a declarative agentic annotation ({@code @SequenceAgent},
 *   {@code @ParallelAgent}, ...) on one of its methods. That is no good for a native image (the interfaces aren't registered
 *   for reflection, so nothing is typed there), and it doesn't see agents built programmatically.</li>
 * </ul>
 * {@code FlowSpanTypingProcessorTests} and {@code IntakeSpanPropagationTests} fail if the typing stops. See the task 11 file,
 * "Typing Flow's spans", for the alternatives that were weighed.
 */
@Singleton
public class FlowSpanTypingProcessor implements SpanProcessor {
	/**
	 * The name Flow gives the span of a workflow task; a task's span name starts with it.
	 */
	public static final String TASK_EXECUTE = "task.execute";

	private static final String WORKFLOW_EXECUTE = "workflow.execute";
	private static final AttributeKey<String> WORKFLOW_NAMESPACE = AttributeKey.stringKey("flow.workflow.namespace");
	private static final AttributeKey<String> WORKFLOW_NAME = AttributeKey.stringKey("flow.workflow.name");
	private static final List<Class<? extends Annotation>> AGENTIC_ANNOTATIONS = List.of(
		SequenceAgent.class, ParallelAgent.class, ParallelMapperAgent.class, ConditionalAgent.class, LoopAgent.class,
		PlannerAgent.class, SupervisorAgent.class
	);

	private final Map<String, Boolean> agenticWorkflows = new ConcurrentHashMap<>();

	@Override
	public void onStart(Context parentContext, ReadWriteSpan span) {
		if (span.getName().startsWith(WORKFLOW_EXECUTE) && isAgentic(span.getAttribute(WORKFLOW_NAMESPACE), span.getAttribute(WORKFLOW_NAME))) {
			span.setAttribute(GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME, GenAiOperationNameIncubatingValues.INVOKE_AGENT);
			span.setAttribute(GenAiIncubatingAttributes.GEN_AI_AGENT_NAME, span.getAttribute(WORKFLOW_NAME));
		}
	}

	// WORKAROUND: reflection on a name derived from Flow's naming; see the class comment
	private boolean isAgentic(String namespace, String name) {
		return (namespace != null) && (name != null) && agenticWorkflows.computeIfAbsent(namespace + "." + name, key -> isGeneratedFromAgent(namespace, name));
	}

	private static boolean isGeneratedFromAgent(String namespace, String name) {
		var agentType = namespace.replace('-', '.') + "." + Arrays.stream(name.split("-"))
			.filter(part -> !part.isEmpty())
			.map(part -> Character.toUpperCase(part.charAt(0)) + part.substring(1))
			.collect(Collectors.joining());

		try {
			return Arrays.stream(Class.forName(agentType, false, Thread.currentThread().getContextClassLoader()).getMethods())
				.anyMatch(method -> AGENTIC_ANNOTATIONS.stream().anyMatch(method::isAnnotationPresent));
		}
		catch (ClassNotFoundException | LinkageError e) {
			return false;
		}
	}

	@Override
	public boolean isStartRequired() {
		return true;
	}

	@Override
	public void onEnd(ReadableSpan span) {
		// No-op: the attribute is stamped onStart
	}

	@Override
	public boolean isEndRequired() {
		return false;
	}

	@Override
	public CompletableResultCode shutdown() {
		return CompletableResultCode.ofSuccess();
	}

	@Override
	public CompletableResultCode forceFlush() {
		return CompletableResultCode.ofSuccess();
	}
}
