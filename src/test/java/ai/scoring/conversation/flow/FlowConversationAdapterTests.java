package ai.scoring.conversation.flow;

import static io.quarkiverse.flow.dsl.FlowDSL.function;
import static io.quarkiverse.flow.dsl.FlowWorkflowBuilder.workflow;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

import ai.scoring.conversation.ConversationContext;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.serverlessworkflow.impl.ServicePriority;
import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.WorkflowInstance;

/**
 * The Flow adapter on a workflow of the test's own, so nothing here depends on the application: a run whose instance
 * metadata carries a conversation id has it on Flow's spans and, as baggage, inside its tasks; a run without it has
 * neither; and runs working at the same time keep their own.
 */
@QuarkusTest
class FlowConversationAdapterTests {
	private static final AttributeKey<String> CONVERSATION_ID = AttributeKey.stringKey(ConversationContext.CONVERSATION_ID_KEY);
	private static final AttributeKey<String> INSTANCE_ID = AttributeKey.stringKey("flow.workflow.instance.id");
	private static final String OTEL_TASK_PROXY = "io.quarkiverse.flow.opentelemetry.runtime.OTelTaskSpanProxy";
	private static final int CONCURRENT_RUNS = 8;
	private static final long WAIT_SECONDS = 30;

	@Inject
	WorkflowApplication application;

	@Inject
	Tracer tracer;

	@Inject
	ConversationContext conversationContext;

	// Static: Flow keeps the first definition of a workflow, and with it the task body of the test instance that built it
	private static final Map<String, Observation> OBSERVATIONS = new ConcurrentHashMap<>();
	private static final AtomicReference<CyclicBarrier> BARRIER = new AtomicReference<>();

	@Test
	void aRunWithTheMetadataCarriesItsIdOnFlowsSpansAndInsideItsTasks() throws Exception {
		var instance = newRun("with-an-id", Optional.of("conversation-a"));

		instance.start().get(WAIT_SECONDS, TimeUnit.SECONDS);

		assertThat(OBSERVATIONS.get("with-an-id"))
			.isEqualTo(new Observation(Optional.of("conversation-a"), Optional.of("conversation-a"), true, Optional.of("conversation-a")));
	}

	@Test
	void aRunIsForgottenOnceItHasEnded() throws Exception {
		var instance = newRun("forgotten", Optional.of("conversation-b"));

		instance.start().get(WAIT_SECONDS, TimeUnit.SECONDS);

		assertThat(conversationIdOf(spanOfInstance(instance.id()))).isEmpty();
	}

	@Test
	void aRunWithoutTheMetadataIsLeftAlone() throws Exception {
		var instance = newRun("without-an-id", Optional.empty());

		instance.start().get(WAIT_SECONDS, TimeUnit.SECONDS);

		assertThat(OBSERVATIONS.get("without-an-id"))
			.isEqualTo(new Observation(Optional.empty(), Optional.empty(), true, Optional.empty()));
	}

	@Test
	void runsWorkingAtTheSameTimeKeepTheirOwnIds() throws Exception {
		var labels = IntStream.range(0, CONCURRENT_RUNS)
			.mapToObj("concurrent-%d"::formatted)
			.toList();

		// Every run's task waits here until all of them are inside it
		BARRIER.set(new CyclicBarrier(CONCURRENT_RUNS));

		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var runs = labels.stream()
				.map(label -> executor.submit(() -> newRun(label, Optional.of("conversation-of-" + label)).start().get(WAIT_SECONDS, TimeUnit.SECONDS)))
				.toList();

			for (var run : runs) {
				run.get(WAIT_SECONDS, TimeUnit.SECONDS);
			}
		}
		finally {
			BARRIER.set(null);
		}

		var expected = labels.stream()
			.collect(Collectors.toUnmodifiableMap(
				Function.identity(),
				label -> new Observation(Optional.of("conversation-of-" + label), Optional.of("conversation-of-" + label), true, Optional.of("conversation-of-" + label))));

		assertThat(OBSERVATIONS).containsAllEntriesOf(expected);
	}

	@Test
	void flowLoadsTheTaskProxyInsideItsOwnOpenTelemetryProxy() {
		var priorities = this.application.callableProxyBuilders()
			.stream()
			.collect(Collectors.toUnmodifiableMap(proxy -> proxy.getClass().getName(), ServicePriority::priority));

		assertThat(priorities).containsKeys(FlowConversationTaskProxy.class.getName(), OTEL_TASK_PROXY);
		assertThat(priorities.get(FlowConversationTaskProxy.class.getName()))
			.as("a lower priority is the inner proxy")
			.isLessThan(priorities.get(OTEL_TASK_PROXY));
	}

	private WorkflowInstance newRun(String label, Optional<String> conversationId) {
		var instance = this.application.workflowDefinition(workflow("flow-conversation-adapter-test")
			.tasks(function("observe", this::observe, String.class, String.class))
			.build())
			.instance(label);

		conversationId.ifPresent(id -> instance.addMetadataIfAbsent(FlowConversationListener.CONVERSATION_ID_METADATA, () -> id));

		return instance;
	}

	// The task's body: what it sees of the conversation, on Flow's task span (the current one) and on a span of its own
	private String observe(String label) {
		Optional.ofNullable(BARRIER.get())
			.ifPresent(FlowConversationAdapterTests::await);

		var ownSpan = this.tracer.spanBuilder("flow-conversation-adapter-test.own").startSpan();
		ownSpan.end();

		var taskSpan = Span.current();

		OBSERVATIONS.put(label, new Observation(
			this.conversationContext.currentId(),
			conversationIdOf(ownSpan),
			spanOf(taskSpan).getName().startsWith(FlowSpanTypingProcessor.TASK_EXECUTE) && (spanOf(taskSpan).getAttribute(INSTANCE_ID) != null),
			conversationIdOf(taskSpan)));

		return label;
	}

	private Span spanOfInstance(String instanceId) {
		var span = this.tracer.spanBuilder("task.execute after the run")
			.setAttribute(INSTANCE_ID, instanceId)
			.startSpan();
		span.end();

		return span;
	}

	private static void await(CyclicBarrier barrier) {
		try {
			barrier.await(WAIT_SECONDS, TimeUnit.SECONDS);
		}
		catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
			throw new IllegalStateException("The runs never worked at the same time", e);
		}
	}

	private static Optional<String> conversationIdOf(Span span) {
		return Optional.ofNullable(spanOf(span).getAttribute(CONVERSATION_ID));
	}

	private static ReadableSpan spanOf(Span span) {
		return (ReadableSpan) span;
	}

	private record Observation(Optional<String> inBaggage, Optional<String> onOwnSpan, boolean isCurrentSpanFlowsTaskSpan, Optional<String> onTaskSpan) {
	}
}
