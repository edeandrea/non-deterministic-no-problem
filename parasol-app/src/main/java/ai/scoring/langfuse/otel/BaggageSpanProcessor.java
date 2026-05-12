package ai.scoring.langfuse.otel;

import jakarta.inject.Singleton;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageEntry;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

@Singleton
public class BaggageSpanProcessor implements SpanProcessor {
	@Override
	public void onStart(Context parentContext, ReadWriteSpan span) {
		Baggage.fromContext(parentContext)
		       .forEach((key, entry) -> processSpan(key, entry, span));
	}

	@Override
	public boolean isStartRequired() {
		return true;
	}

	@Override
	public void onEnd(ReadableSpan span) {
	}

	@Override
	public boolean isEndRequired() {
		return false;
	}

	private static void processSpan(String key, BaggageEntry entry, ReadWriteSpan span) {
		if (SpanHelper.isGenAiSpan(span.toSpanData())) {
			span.setAttribute(key, entry.getValue());
		}
	}
}
