package ai.scoring.conversation.flow;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

import ai.scoring.conversation.ConversationContext;
import io.serverlessworkflow.impl.WorkflowInstanceData;
import io.serverlessworkflow.impl.lifecycle.WorkflowCancelledEvent;
import io.serverlessworkflow.impl.lifecycle.WorkflowCompletedEvent;
import io.serverlessworkflow.impl.lifecycle.WorkflowEvent;
import io.serverlessworkflow.impl.lifecycle.WorkflowExecutionListener;
import io.serverlessworkflow.impl.lifecycle.WorkflowFailedEvent;
import io.serverlessworkflow.impl.lifecycle.WorkflowStartedEvent;

/**
 * The Flow adapter of the conversation core: gives every span of a workflow run its conversation id, with no code in the
 * application's steps.
 * <p>
 * <b>The contract with the application is instance metadata.</b> A run belongs to a conversation when its instance
 * carries the id under {@link #CONVERSATION_ID_METADATA} ({@code gen_ai.conversation.id}), which the application sets
 * between creating the instance and starting it:
 * <pre>
 * var instance = flow.instance(input);
 * instance.addMetadataIfAbsent("gen_ai.conversation.id", () -> conversationId);
 * instance.start();
 * </pre>
 * The application needs no type from this library for that, only the key. A run without the metadata (any other
 * workflow, or the agentic sub-workflows Flow generates) is left alone: nothing is recorded, wrapped or stamped.
 * <p>
 * The adapter has three parts:
 * <ul>
 *   <li>this listener records instance id → conversation id when a run starts, and forgets it when the run completes,
 *   fails or is canceled;</li>
 *   <li>{@link FlowConversationSpanProcessor} stamps Flow's own spans ({@code workflow.create},
 *   {@code workflow.execute}, {@code task.execute}) from that record;</li>
 *   <li>{@link FlowConversationTaskProxy} makes the id current, as baggage, around each call task's body, so the spans
 *   started inside a step (AI calls, JDBC, mail, agentic sub-workflows) carry it too.</li>
 * </ul>
 * <b>What it relies on in Flow</b> (public SPIs only; {@code FlowConversationAdapterTests} fails if any of it changes):
 * <ul>
 *   <li>instance metadata is kept in memory for as long as the run is alive in this JVM, which is as long as the
 *   adapter needs it;</li>
 *   <li>{@code start()} notifies listeners on the caller's thread, in ascending {@link #priority()} order. This one's
 *   is below the default (1000) that Flow's OpenTelemetry listener has, so the run is recorded before that listener
 *   starts the run's first spans.</li>
 * </ul>
 */
@ApplicationScoped
public class FlowConversationListener implements WorkflowExecutionListener {
	/**
	 * The instance metadata key a run's conversation id is read from: {@code gen_ai.conversation.id}.
	 */
	public static final String CONVERSATION_ID_METADATA = ConversationContext.CONVERSATION_ID_KEY;

	// Below ServicePriority's default of 1000, which Flow's OpenTelemetry listener has
	private static final int PRIORITY = 0;

	private final Map<String, String> conversationIds = new ConcurrentHashMap<>();

	static Optional<String> conversationIdOf(WorkflowInstanceData instance) {
		return instance.findMetadata(CONVERSATION_ID_METADATA, String.class);
	}

	Optional<String> conversationIdOfInstance(String instanceId) {
		return Optional.ofNullable(this.conversationIds.get(instanceId));
	}

	@Override
	public int priority() {
		return PRIORITY;
	}

	@Override
	public void onWorkflowStarted(WorkflowStartedEvent event) {
		var instance = event.workflowContext().instanceData();

		conversationIdOf(instance)
			.ifPresent(conversationId -> this.conversationIds.put(instance.id(), conversationId));
	}

	@Override
	public void onWorkflowCompleted(WorkflowCompletedEvent event) {
		forget(event);
	}

	@Override
	public void onWorkflowFailed(WorkflowFailedEvent event) {
		forget(event);
	}

	@Override
	public void onWorkflowCancelled(WorkflowCancelledEvent event) {
		forget(event);
	}

	private void forget(WorkflowEvent event) {
		this.conversationIds.remove(event.workflowContext().instanceData().id());
	}
}
