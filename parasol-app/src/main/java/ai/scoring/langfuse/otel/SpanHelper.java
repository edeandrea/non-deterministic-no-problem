package ai.scoring.langfuse.otel;

import java.util.Collection;
import java.util.function.Predicate;
import java.util.stream.Stream;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;

final class SpanHelper {
	private SpanHelper() {}

	static boolean isGenAiSpan(SpanData span) {
		return spanAttributeKeysMatchCriteria(
			span,
			key -> key.getKey().startsWith("gen_ai.")
		);
	}

	static boolean isLLMCallSpan(SpanData span) {
		return spanAttributeKeysMatchCriteria(
			span,
			key -> key.getKey().equalsIgnoreCase("gen_ai.completion")
		);
	}

	static Stream<SpanData> getLLMSpansCallSpans(Collection<SpanData> spans) {
		return getFilteredSpans(spans, SpanHelper::isLLMCallSpan);
	}

	/**
	 * Includes all spans from any trace that contains at least one {@code gen_ai.*} span.
	 * Non-AI traces are dropped entirely.
	 */
	static Stream<SpanData> filterAllSpansFromAiTraces(Collection<SpanData> spans) {
		return getFilteredSpans(spans, SpanHelper::isGenAiSpan);
	}

	private static Stream<SpanData> getFilteredSpans(Collection<SpanData> spans, Predicate<SpanData> spanFilter) {
		// The spans could "span" different traces, so make sure we only do it for each trace
		return spans.stream().filter(spanFilter);
	}

	private static boolean spanAttributeKeysMatchCriteria(SpanData span, Predicate<AttributeKey> predicate) {
		return span.getAttributes()
		           .asMap()
		           .keySet()
		           .stream()
		           .anyMatch(predicate);
	}
}
