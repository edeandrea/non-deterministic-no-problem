package ai.scoring.conversation;

import java.util.Optional;

import jakarta.inject.Singleton;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes;

/**
 * A {@code SpanProcessor} implementation that propagates the Conversation ID from the
 * OpenTelemetry baggage into the span attributes when a span starts. This processor is
 * used to ensure that spans are enriched with a Conversation ID, enabling consistent
 * tracking and observability of conversation-related spans.
 *
 * This class retrieves the Conversation ID stored in the OpenTelemetry baggage
 * and sets it as a span attribute using the key provided by
 * {@code GenAiIncubatingAttributes.GEN_AI_CONVERSATION_ID}.
 *
 * Features:
 * - Automatically propagates the Conversation ID from the baggage into span attributes.
 * - Optimized for no-op behavior on span end to minimize overhead.
 * - Provides utility methods for graceful shutdown and flush operations.
 */
@Singleton
public class ConversationIdBaggageSpanProcessor implements SpanProcessor {
	private static final String CONVERSATION_ID_KEY = GenAiIncubatingAttributes.GEN_AI_CONVERSATION_ID.getKey();

	@Override
	public void onStart(Context parentContext, ReadWriteSpan span) {
		Optional.ofNullable(Baggage.fromContext(parentContext).getEntryValue(CONVERSATION_ID_KEY))
			.ifPresent(conversationId -> span.setAttribute(GenAiIncubatingAttributes.GEN_AI_CONVERSATION_ID, conversationId));
	}

	@Override
	public boolean isStartRequired() {
		return true;
	}

	@Override
	public boolean isEndRequired() {
		return false;
	}

	@Override
	public void onEnd(ReadableSpan span) {
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
