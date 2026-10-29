package ai.scoring.langfuse.otel;

import jakarta.inject.Singleton;

import ai.scoring.conversation.flow.FlowSpanTypingProcessor;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

/**
 * Makes Flow's {@code task.execute} spans a Langfuse {@code chain} observation (a step, usually plain Java, isn't an agent),
 * with {@code langfuse.observation.type=chain}. Since quarkus-langfuse 0.9.1, the {@code AI_ONLY} filter retains spans with
 * {@code langfuse.observation.*} attributes directly; no synthetic GenAI operation name is needed.
 */
@Singleton
public class LangfuseFlowSpanTypingProcessor implements SpanProcessor {
	public static final AttributeKey<String> LANGFUSE_OBSERVATION_TYPE = AttributeKey.stringKey("langfuse.observation.type");
	public static final String CHAIN = "chain";

	@Override
	public void onStart(Context parentContext, ReadWriteSpan span) {
		if (span.getName().startsWith(FlowSpanTypingProcessor.TASK_EXECUTE)) {
			span.setAttribute(LANGFUSE_OBSERVATION_TYPE, CHAIN);
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
