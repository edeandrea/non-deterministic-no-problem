package ai.scoring.langfuse.otel;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import ai.scoring.langfuse.config.LangfuseConfig;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

public class LangfuseSpanExporter implements SpanExporter {
	private final SpanExporter delegate;
	private final LangfuseConfig config;

	LangfuseSpanExporter(SpanExporter delegate, LangfuseConfig config) {
		this.delegate = delegate;
		this.config = config;
	}

	@Override
	public CompletableResultCode export(Collection<SpanData> spans) {
		var filtered = this.config.onlyIncludeAiSpans() ?
		               filterAiSpansWithAncestors(spans) :
		               filterAllSpansFromAiTraces(spans);

		return filtered.isEmpty() ?
		       CompletableResultCode.ofSuccess() :
		       this.delegate.export(filtered);
	}

	@Override
	public CompletableResultCode flush() {
		return this.delegate.flush();
	}

	@Override
	public CompletableResultCode shutdown() {
		return this.delegate.shutdown();
	}

	/**
	 * Includes all spans from any trace that contains at least one {@code gen_ai.*} span.
	 * Non-AI traces are dropped entirely.
	 */
	private static Collection<SpanData> filterAllSpansFromAiTraces(Collection<SpanData> spans) {
		return spans.stream()
			.collect(Collectors.groupingBy(SpanData::getTraceId))
			.values()
			.stream()
			.filter(group -> group.stream().anyMatch(LangfuseSpanExporter::isGenAiSpan))
			.flatMap(Collection::stream)
			.toList();
	}

	/**
	 * Includes only {@code gen_ai.*} spans and their ancestor chain up to the trace root.
	 * Unrelated siblings (DB queries, HTTP calls, etc.) are excluded.
	 * Uses {@link HashSet#add} as memoization — ancestor walks short-circuit
	 * when they reach a span already visited by a previous walk.
	 */
	private static Collection<SpanData> filterAiSpansWithAncestors(Collection<SpanData> spans) {
		var spanById = spans.stream()
			.collect(Collectors.toMap(SpanData::getSpanId, Function.identity(), (a, b) -> a));

		var seen = new HashSet<String>();

		return spans.stream()
			.filter(LangfuseSpanExporter::isGenAiSpan)
			.flatMap(span -> Stream.iterate(span, Objects::nonNull, current -> spanById.get(current.getParentSpanId()))
				.takeWhile(current -> seen.add(current.getSpanId())))
			.toList();
	}

	private static boolean isGenAiSpan(SpanData span) {
		return span.getAttributes()
		           .asMap()
		           .keySet()
		           .stream()
		           .anyMatch(key -> key.getKey().startsWith("gen_ai."));
	}
}
