package ai.scoring.conversation;

import static ai.scoring.conversation.ConversationContext.CONVERSATION_ID_KEY;

import java.util.Optional;

import jakarta.inject.Singleton;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

/**
 * Copies the conversation id from the baggage onto every span as {@code gen_ai.conversation.id}, so every exporter
 * (Langfuse, Tempo, the tests' in-memory one) sees it. It writes the same attribute, with the same value, as
 * quarkus-langfuse's own span processor, so the two never conflict.
 * <p>
 * The id is read from the parent context handed to {@link #onStart}, not {@link Context#current()}: a span can be
 * started with an explicit parent.
 */
@Singleton
public class ConversationIdSpanProcessor implements SpanProcessor {
	private static final AttributeKey<String> CONVERSATION_ID = AttributeKey.stringKey(CONVERSATION_ID_KEY);

	@Override
	public void onStart(Context parentContext, ReadWriteSpan span) {
		Optional.ofNullable(Baggage.fromContext(parentContext).getEntryValue(CONVERSATION_ID_KEY))
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
