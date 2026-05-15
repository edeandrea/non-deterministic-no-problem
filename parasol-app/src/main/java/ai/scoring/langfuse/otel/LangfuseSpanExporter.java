package ai.scoring.langfuse.otel;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jboss.logging.Logger;

import ai.scoring.conversation.ConversationBoundary;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

public class LangfuseSpanExporter implements SpanExporter {
	private static final Logger LOG = Logger.getLogger(LangfuseSpanExporter.class);

	private static final String PROMPT_ATTRIBUTE_KEY = "gen_ai.prompt";
	private static final String COMPLETION_ATTRIBUTE_KEY = "gen_ai.completion";
	private static final String LANGFUSE_TRACE_INPUT_ATTRIBUTE_KEY = "langfuse.trace.input";
	private static final String LANGFUSE_TRACE_OUTPUT_ATTRIBUTE_KEY = "langfuse.trace.output";
	
	private final SpanExporter delegate;

	LangfuseSpanExporter(SpanExporter delegate) {
		this.delegate = delegate;
	}

	@Override
	public CompletableResultCode export(Collection<SpanData> spans) {
		var exportMap = filterAiSpansWithAncestors(spans);

		exportMap.values()
		         .stream()
		         .collect(Collectors.groupingBy(SpanData::getTraceId))
		         .forEach((traceId, s) -> {
							 findFirstGenAiSpan(s)
								 .ifPresent(llmSpan -> exportMap.put(llmSpan.getSpanId(), computeLangfuseSpan(llmSpan)));

							 findRootSpan(traceId, s)
								 .ifPresent(rootSpan -> exportMap.put(rootSpan.getSpanId(), computeLangfuseSpan(rootSpan)));
		         });

		return exportMap.isEmpty() ?
		       CompletableResultCode.ofSuccess() :
		       this.delegate.export(exportMap.values());
	}

	private static Optional<SpanData> findFirstGenAiSpan(Collection<SpanData> llmSpans) {
		return llmSpans.stream()
			.filter(LangfuseSpanExporter::isGenAiSpan)
			.min(Comparator.comparingLong(SpanData::getStartEpochNanos));
	}

	private static Optional<SpanData> findRootSpan(String traceId, Collection<SpanData> spans) {
		return spans.stream()
			.filter(s -> isRootSpanForTrace(traceId, s))
			.findFirst();
	}

	private static boolean isRootSpan(SpanData span) {
		return !span.getParentSpanContext().isValid();
	}

	private static boolean isRootSpanForTrace(String traceId, SpanData span) {
		return isRootSpan(span) && span.getTraceId().equals(traceId);
	}

	private static SpanData computeLangfuseSpan(SpanData span) {
 		var attributes = span.getAttributes();
		var attributesBuilder = attributes.toBuilder();

		Optional.ofNullable(attributes.get(AttributeKey.stringKey(PROMPT_ATTRIBUTE_KEY)))
			.ifPresent(v -> attributesBuilder.put(LANGFUSE_TRACE_INPUT_ATTRIBUTE_KEY, v));

		Optional.ofNullable(attributes.get(AttributeKey.stringKey(COMPLETION_ATTRIBUTE_KEY)))
			.ifPresent(v -> attributesBuilder.put(LANGFUSE_TRACE_OUTPUT_ATTRIBUTE_KEY, v));

		var newAttributes = attributesBuilder.build();
		return newAttributes.isEmpty() ? span : new EnrichedSpanData(span, newAttributes);
	}

	private static Map<String, SpanData> filterAiSpansWithAncestors(Collection<SpanData> spans) {
		var spansById = spans.stream()
			.collect(Collectors.toUnmodifiableMap(SpanData::getSpanId, Function.identity()));

		var seen = new HashSet<String>();
		var result = new HashMap<String, SpanData>();

		spansById.values().stream()
			.filter(LangfuseSpanExporter::isGenAiSpan)
			.flatMap(span -> Stream.iterate(span, Objects::nonNull, current -> spansById.get(current.getParentSpanId()))
				.takeWhile(current -> seen.add(current.getSpanId())))
			.forEach(span -> result.put(span.getSpanId(), span));

		return result;
	}

	@Override
	public CompletableResultCode flush() {
		return this.delegate.flush();
	}

	@Override
	public CompletableResultCode shutdown() {
		return this.delegate.shutdown();
	}
	
	private static boolean isGenAiSpan(SpanData span) {
		return spanAttributeKeysMatchCriteria(
			span,
			key -> !ConversationBoundary.CONVERSATION_SPAN_NAME.equalsIgnoreCase(key.getKey()) && key.getKey().startsWith("gen_ai.")
		);
	}

	private static boolean spanAttributeKeysMatchCriteria(SpanData span, Predicate<AttributeKey> predicate) {
		return span.getAttributes()
		           .asMap()
		           .keySet()
		           .stream()
		           .anyMatch(predicate);
	}

	private static final class EnrichedSpanData extends DelegatingSpanData {
		private final Attributes mergedAttributes;

		private EnrichedSpanData(SpanData delegate, Attributes mergedAttributes) {
			super(delegate);
			this.mergedAttributes = mergedAttributes;
		}

		@Override
		public Attributes getAttributes() {
			return this.mergedAttributes;
		}

		@Override
		public int getTotalAttributeCount() {
			return mergedAttributes.size();
		}
	}
}
