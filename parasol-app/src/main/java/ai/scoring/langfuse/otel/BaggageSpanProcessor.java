package ai.scoring.langfuse.otel;

import jakarta.inject.Singleton;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

@Singleton
public class BaggageSpanProcessor implements SpanProcessor {
	@Override
	public void onStart(Context parentContext, ReadWriteSpan span) {
		Baggage.fromContext(parentContext)
		       .forEach((key, entry) -> span.setAttribute(key, entry.getValue()));
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
}
