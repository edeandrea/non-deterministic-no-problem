package ai.scoring.conversation.flow;

import static ai.scoring.conversation.ConversationContext.CONVERSATION_ID_KEY;

import java.util.Optional;

import jakarta.inject.Singleton;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

/**
 * Stamps {@code gen_ai.conversation.id} on Flow's own spans of a run that has one: {@code workflow.create},
 * {@code workflow.execute} and every {@code task.execute}, recognized by their {@code flow.workflow.instance.id}. Flow
 * builds those from the context the run was started in, so they never see the baggage {@link FlowConversationTaskProxy}
 * adds inside a task. See {@link FlowConversationListener} for the contract.
 */
@Singleton
public class FlowConversationSpanProcessor implements SpanProcessor {
	private static final AttributeKey<String> INSTANCE_ID = AttributeKey.stringKey("flow.workflow.instance.id");
	private static final AttributeKey<String> CONVERSATION_ID = AttributeKey.stringKey(CONVERSATION_ID_KEY);

	private final FlowConversationListener conversations;

	FlowConversationSpanProcessor(FlowConversationListener conversations) {
		this.conversations = conversations;
	}

	@Override
	public void onStart(Context parentContext, ReadWriteSpan span) {
		Optional.ofNullable(span.getAttribute(INSTANCE_ID))
			.flatMap(this.conversations::conversationIdOfInstance)
			.ifPresent(conversationId -> span.setAttribute(CONVERSATION_ID, conversationId));
	}

	@Override
	public boolean isStartRequired() {
		return true;
	}

	@Override
	public void onEnd(ReadableSpan span) {
		// No-op: the id is stamped onStart
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
