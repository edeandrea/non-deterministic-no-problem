package ai.scoring.langfuse.otel;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import ai.scoring.langfuse.config.LangfuseConfig;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
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
		var spanById = spans.stream()
			.collect(Collectors.toMap(SpanData::getSpanId, Function.identity(), (a, b) -> a));

		var exportMap = filterAiSpansWithAncestors(spans, spanById);

		exportMap.values().stream()
			.filter(SpanHelper::isLLMCallSpan)
			.collect(Collectors.groupingBy(SpanData::getTraceId))
			.forEach((traceId, llmSpans) -> {
				var firstLlmSpan = findFirstLlmSpan(llmSpans);

				findRootSpan(traceId, exportMap.values())
					.ifPresentOrElse(
						root -> exportMap.put(root.getSpanId(), EnrichedSpanData.withGenAiAttributes(root, firstLlmSpan)),
						() -> exportMap.put(firstLlmSpan.getSpanId(), EnrichedSpanData.asRoot(firstLlmSpan))
					);
		});

		var toExport = exportMap.values().stream().toList();

		return toExport.isEmpty() ?
		       CompletableResultCode.ofSuccess() :
		       this.delegate.export(toExport);
	}

	private static HashMap<String, SpanData> filterAiSpansWithAncestors(Collection<SpanData> spans, Map<String, SpanData> spanById) {
		var seen = new HashSet<String>();
		var result = new HashMap<String, SpanData>();

		spans.stream()
			.filter(SpanHelper::isGenAiSpan)
			.flatMap(span -> Stream.iterate(span, Objects::nonNull, current -> spanById.get(current.getParentSpanId()))
				.takeWhile(current -> seen.add(current.getSpanId())))
			.forEach(span -> result.put(span.getSpanId(), span));

		return result;
	}

	private static SpanData findFirstLlmSpan(List<SpanData> llmSpans) {
		return llmSpans.stream()
			.min(Comparator.comparingLong(SpanData::getStartEpochNanos))
			.orElseThrow();
	}

	private static Optional<SpanData> findRootSpan(String traceId, Collection<SpanData> spans) {
		return spans.stream()
			.filter(s -> !s.getParentSpanContext().isValid() && s.getTraceId().equals(traceId))
			.findFirst();
	}

	@Override
	public CompletableResultCode flush() {
		return this.delegate.flush();
	}

	@Override
	public CompletableResultCode shutdown() {
		return this.delegate.shutdown();
	}

	private static final class EnrichedSpanData extends DelegatingSpanData {
		private final Attributes mergedAttributes;
		private final SpanContext parentSpanContext;

		private EnrichedSpanData(SpanData delegate, Attributes mergedAttributes) {
			this(delegate, mergedAttributes, null);
		}

		private EnrichedSpanData(SpanData delegate, Attributes mergedAttributes, SpanContext parentSpanContext) {
			super(delegate);
			this.mergedAttributes = mergedAttributes;
			this.parentSpanContext = parentSpanContext;
		}

		static EnrichedSpanData withGenAiAttributes(SpanData delegate, SpanData genAiSource) {
			var builder = delegate.getAttributes().toBuilder();

			genAiSource.getAttributes().forEach((key, value) -> {
				if (key.getKey().startsWith("gen_ai.")) {
					builder.put((AttributeKey) key, value);
				}
			});

			return new EnrichedSpanData(delegate, builder.build());
		}

		static EnrichedSpanData asRoot(SpanData delegate) {
			return new EnrichedSpanData(delegate, delegate.getAttributes(), SpanContext.getInvalid());
		}

		@Override
		public Attributes getAttributes() {
			return mergedAttributes;
		}

		@Override
		public SpanContext getParentSpanContext() {
			return (parentSpanContext != null) ? parentSpanContext : super.getParentSpanContext();
		}

		@Override
		public int getTotalAttributeCount() {
			return mergedAttributes.size();
		}
	}
}
