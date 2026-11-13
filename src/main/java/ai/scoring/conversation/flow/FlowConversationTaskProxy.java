package ai.scoring.conversation.flow;

import jakarta.enterprise.inject.spi.CDI;

import ai.scoring.conversation.ConversationContext;
import io.serverlessworkflow.impl.executors.CallableTask;
import io.serverlessworkflow.impl.executors.CallableTaskProxyBuilder;

/**
 * Makes a run's conversation id current, as baggage, while a call task's body runs. See
 * {@link FlowConversationListener} for the contract; a run without a conversation id is passed straight through.
 * <p>
 * Not a CDI bean: Flow loads it with {@code ServiceLoader}, from
 * {@code META-INF/services/io.serverlessworkflow.impl.executors.CallableTaskProxyBuilder}. That is why it looks the
 * {@link ConversationContext} up, when a task of a run that has a conversation id is called.
 * <p>
 * <b>Its priority must stay below 100</b>, the priority of quarkus-flow-opentelemetry's {@code OTelTaskSpanProxy}
 * (package-private there, so it can't be referenced). Proxies wrap in ascending priority order, so the lower one is
 * innermost: this one runs inside the scope in which that proxy made the task's span current, and adds the baggage to
 * it. Above 100, that proxy's context would replace the baggage.
 */
public class FlowConversationTaskProxy implements CallableTaskProxyBuilder {
	private static final int PRIORITY = 50;

	@Override
	public int priority() {
		return PRIORITY;
	}

	@Override
	public CallableTask build(CallableTask delegate) {
		return (workflowContext, taskContext, input) -> FlowConversationListener.conversationIdOf(workflowContext.instanceData())
			.map(conversationId -> conversationContext().callIn(conversationId, () -> delegate.apply(workflowContext, taskContext, input)))
			.orElseGet(() -> delegate.apply(workflowContext, taskContext, input));
	}

	private static ConversationContext conversationContext() {
		return CDI.current()
			.select(ConversationContext.class)
			.get();
	}
}
