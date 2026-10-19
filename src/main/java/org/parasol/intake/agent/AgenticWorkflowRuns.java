package org.parasol.intake.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkiverse.flow.langchain4j.workflow.service.FlowPlanner;
import io.quarkus.logging.Log;
import dev.langchain4j.agentic.scope.AgenticScope;
import io.serverlessworkflow.impl.WorkflowInstance;
import io.serverlessworkflow.impl.lifecycle.WorkflowCancelledEvent;
import io.serverlessworkflow.impl.lifecycle.WorkflowCompletedEvent;
import io.serverlessworkflow.impl.lifecycle.WorkflowExecutionListener;
import io.serverlessworkflow.impl.lifecycle.WorkflowFailedEvent;
import io.serverlessworkflow.impl.lifecycle.WorkflowStartedEvent;

/**
 * Tracks the Flow runs that quarkus-flow generates for agentic systems (their input is the {@link AgenticScope}), so
 * that {@link FailedAgenticSystemListener} can end the runs of an agentic system whose root invocation failed.
 * <p>
 * Each generated run's task blocks a worker thread in {@code FlowPlanner.executeAgent(i).join()} until the planner
 * completes that agent call. When the agentic system fails, nothing ever does, so this fails the pending calls itself:
 * the threads are released, the runs fault, and Flow deletes their rows as it does for any ended run. {@code FlowPlanner}
 * has no public way to do that, so it reads the planner's two private collections of pending calls by reflection. If
 * that ever stops working (another quarkus-flow version), it cancels the runs instead, which deletes their rows but
 * leaves each thread blocked, and logs a warning.
 *
 * @deprecated A workaround for a quarkus-flow-langchain4j 1.2.0 bug: when an agent throws, {@code FlowPlanner} never
 * completes the continuation the generated workflow's task waits on, so the run never ends. It stays active in memory,
 * keeps a worker thread blocked, and its Flow rows (including the agentic scope, which holds the correspondence text)
 * stay in the database. Upstream: <a href="https://github.com/quarkiverse/quarkus-flow/issues/1096">quarkiverse/quarkus-flow#1096</a>,
 * fixed by <a href="https://github.com/quarkiverse/quarkus-flow/pull/1095">quarkiverse/quarkus-flow#1095</a>
 * (it fails the pending calls the same way, from {@code FlowAgentContextListener}). Remove this class,
 * {@link FailedAgenticSystemListener} and the supplier on {@link ClaimsMailboxAgent} once a quarkus-flow release
 * includes it.
 */
@Deprecated
@ApplicationScoped
class AgenticWorkflowRuns implements WorkflowExecutionListener {
	private static final List<String> PENDING_CALL_FIELDS = List.of("currentExchanges", "agentExchangeQueue");
	private static final String CONTINUATION = "continuation";

	private final Map<String, AgenticRun> runs = new ConcurrentHashMap<>();

	@Override
	public void onWorkflowStarted(WorkflowStartedEvent event) {
		var context = event.workflowContext();

		if ((context.instanceData() instanceof WorkflowInstance run) && (run.input().asJavaObject() instanceof AgenticScope scope)) {
			this.runs.put(run.id(), new AgenticRun(run, context.definition().id().name(), scope));
		}
	}

	@Override
	public void onWorkflowCompleted(WorkflowCompletedEvent event) {
		this.runs.remove(event.workflowContext().instanceData().id());
	}

	@Override
	public void onWorkflowFailed(WorkflowFailedEvent event) {
		this.runs.remove(event.workflowContext().instanceData().id());
	}

	@Override
	public void onWorkflowCancelled(WorkflowCancelledEvent event) {
		this.runs.remove(event.workflowContext().instanceData().id());
	}

	/**
	 * Ends the runs of an agentic system that are still active: every level of a nested agentic system shares the
	 * root's scope, so this ends them all.
	 *
	 * @param scope the failed agentic system's scope
	 * @param cause why it failed; each run faults with it
	 */
	void failRunsOf(AgenticScope scope, Throwable cause) {
		var abandoned = this.runs.values()
			.stream()
			.filter(run -> run.scope() == scope)
			.toList();

		for (var run : abandoned) {
			if ((scope.executionContext(run.instance().id()) instanceof FlowPlanner planner) && failPendingAgentCalls(planner, cause)) {
				Log.warnf("Failed Flow run %s (%s): its agentic system failed, and quarkus-flow 1.2.0 never ends such a run on its own",
					run.instance().id(), run.workflowName());
			}
			else if (run.instance().cancel()) {
				Log.warnf("Canceled Flow run %s (%s): its agentic system failed, and its pending agent calls couldn't be failed, so a worker thread stays blocked",
					run.instance().id(), run.workflowName());
			}
		}
	}

	private static boolean failPendingAgentCalls(FlowPlanner planner, Throwable cause) {
		var isFailed = false;

		try {
			for (var continuation : pendingContinuations(planner)) {
				continuation.completeExceptionally(cause);
			}

			isFailed = true;
		}
		catch (ReflectiveOperationException | RuntimeException e) {
			Log.warnf(e, "Couldn't read FlowPlanner's pending agent calls; this workaround no longer matches quarkus-flow's internals");
		}

		return isFailed;
	}

	// FlowPlanner keeps the calls it has handed to LangChain4j in a Map and the ones not yet taken in a queue; each
	// element is a private record whose continuation is what the generated workflow's task joins on
	private static List<CompletableFuture<?>> pendingContinuations(FlowPlanner planner) throws ReflectiveOperationException {
		var continuations = new ArrayList<CompletableFuture<?>>();

		for (var fieldName : PENDING_CALL_FIELDS) {
			var field = FlowPlanner.class.getDeclaredField(fieldName);
			field.setAccessible(true);

			var calls = switch (field.get(planner)) {
				case Map<?, ?> map -> map.values();
				case Collection<?> collection -> collection;
				case null -> List.of();
				default -> throw new IllegalStateException("Unexpected type of FlowPlanner." + fieldName);
			};

			for (var call : calls) {
				var accessor = call.getClass().getDeclaredMethod(CONTINUATION);
				accessor.setAccessible(true);
				continuations.add((CompletableFuture<?>) accessor.invoke(call));
			}
		}

		return continuations;
	}

	private record AgenticRun(WorkflowInstance instance, String workflowName, AgenticScope scope) {
	}
}
