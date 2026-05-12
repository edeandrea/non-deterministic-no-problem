package ai.scoring.langfuse.otel;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import ai.scoring.langfuse.config.LangfuseConfig;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
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
//		var filtered = this.config.onlyIncludeAiSpans() ?
//		               filterAiSpansWithAncestors(spans) :
//		               SpanHelper.filterAllSpansFromAiTraces(spans);
//
//		filtered.forEach(span -> Log.infof("Exporting span %s: %s", span.getSpanId(), span.getAttributes().asMap()));

		var spansBySpanId = new HashMap<String, SpanData>();
		var llmSpansByTrace = SpanHelper.getLLMSpansCallSpans(spans)
			.peek(span -> spansBySpanId.put(span.getSpanId(), span))
			.collect(Collectors.groupingBy(SpanData::getTraceId));

		llmSpansByTrace.values()
			.stream()
			.map(s -> s.stream()
				.min(Comparator.comparingLong(SpanData::getStartEpochNanos))
			)
			.filter(Optional::isPresent)
			.map(Optional::get)
			.forEach(firstLLMSpan -> spansBySpanId.put(firstLLMSpan.getSpanId(), new NoParentSpan(firstLLMSpan)));

		var allSpans = llmSpansByTrace.values().stream()
			.flatMap(Collection::stream)
			.map(span -> spansBySpanId.get(span.getSpanId()))
			.toList();

		return allSpans.isEmpty() ?
		       CompletableResultCode.ofSuccess() :
		       this.delegate.export(allSpans);
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
			.filter(SpanHelper::isGenAiSpan)
			.flatMap(span -> Stream.iterate(span, Objects::nonNull, current -> spanById.get(current.getParentSpanId()))
				.takeWhile(current -> seen.add(current.getSpanId())))
			.toList();
	}

	private static final class NoParentSpan extends DelegatingSpanData {
		private NoParentSpan(SpanData delegate) {
			super(delegate);
		}

		@Override
		public SpanContext getParentSpanContext() {
			return SpanContext.getInvalid();
		}
	}
}
