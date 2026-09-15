package ai.scoring.langfuse.session;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.langfuse.api.model.ObservationV2;

import ai.scoring.langfuse.otel.AiServiceAttributes;

/**
 * A single request/response exchange within a conversation, along with the name of the Langfuse dataset
 * the exchange should be recorded into.
 * <p>
 * The dataset name is the LangChain4j AI service span name, verbatim:
 * {@code langchain4j.aiservices.<AiServiceClassName>.<methodName>}.
 * This is the same name the drift detection guardrail derives from LangChain4j's {@code InvocationContext}.
 * It is resolved, in order of preference, from:
 * <ol>
 *   <li>The {@code langfuse.dataset.name} (or {@code ai.service.class} + {@code ai.service.method}) attributes cascaded
 *       onto the generation span by {@code AiServiceDatasetSpanProcessor} (see {@link AiServiceAttributes}) and surfaced
 *       as observation metadata</li>
 *   <li>The same attributes on any ancestor observation</li>
 *   <li>The name of the nearest ancestor observation named {@code langchain4j.aiservices.*}</li>
 *   <li>The trace name</li>
 *   <li>The observation's own name</li>
 * </ol>
 */
public record ConversationExchange(
	String datasetName,
	String traceName,
	String traceId,
	String input,
	String output
) {
	private static final String DATASET_NAME_KEY = AiServiceAttributes.DATASET_NAME.getKey();
	private static final String AI_SERVICE_CLASS_KEY = AiServiceAttributes.AI_SERVICE_CLASS.getKey();
	private static final String AI_SERVICE_METHOD_KEY = AiServiceAttributes.AI_SERVICE_METHOD.getKey();
	private static final String AI_SERVICES_PREFIX = AiServiceAttributes.AI_SERVICES_PREFIX;

	// Langfuse's OTLP ingestion has, across versions, surfaced span attributes in observation metadata in three shapes:
	//   { "langfuse.dataset.name": ... }                       (flat)
	//   { "attributes.langfuse.dataset.name": ... }            (flat, prefixed)
	//   { "attributes": { "langfuse.dataset.name": ... } }    (nested map)
	// datasetNameFromMetadata() checks all three so we're not coupled to a particular Langfuse release.
	private static final String ATTRIBUTES_KEY = "attributes";
	private static final String ATTRIBUTES_PREFIX = ATTRIBUTES_KEY + ".";
	private static final String DEFAULT_NAME = "default";

	/**
	 * Creates an exchange from a lone observation, without any knowledge of its ancestors.
	 */
	public static ConversationExchange from(ObservationV2 observation) {
		// No-op lookup: every parent is "unknown", so resolution skips the hierarchy steps
		return from(observation, id -> Optional.empty());
	}

	/**
	 * Creates an exchange from an observation, using {@code parentLookup} to walk up the observation hierarchy
	 * when the observation itself doesn't carry the dataset coordinates.
	 */
	public static ConversationExchange from(ObservationV2 observation, Function<String, Optional<ObservationV2>> parentLookup) {
		var traceName = nonBlank(observation.getTraceName())
			.or(() -> nonBlank(observation.getName()))
			.orElse(DEFAULT_NAME);

		return new ConversationExchange(
			resolveDatasetName(observation, parentLookup),
			traceName,
			observation.getTraceId(),
			String.valueOf(observation.getInput()),
			String.valueOf(observation.getOutput())
		);
	}

	/**
	 * Creates a parent lookup function backed by an in-memory collection of observations (e.g. all the observations
	 * for a session), keyed by observation id.
	 */
	public static Function<String, Optional<ObservationV2>> hierarchyOf(Collection<ObservationV2> observations) {
		// Index once so each parent hop during resolution is O(1). Duplicate ids shouldn't happen, but if they do keep
		// the first rather than blowing up the whole session's scoring.
		var byId = observations.stream()
			.filter(obs -> obs.getId() != null)
			.collect(Collectors.toUnmodifiableMap(ObservationV2::getId, Function.identity(), (first, second) -> first));

		// Null-safe: a root observation has a null parentObservationId
		return id -> Optional.ofNullable(id).map(byId::get);
	}

	/**
	 * Resolution order (first hit wins):
	 * <ol>
	 *   <li>this observation's own metadata</li>
	 *   <li>any ancestor's metadata (nearest first)</li>
	 *   <li>the name of the nearest ancestor that is an AI service span</li>
	 *   <li>the trace name</li>
	 *   <li>this observation's own name</li>
	 * </ol>
	 * Steps 1-2 are what we expect once Langfuse surfaces the cascaded span attributes as metadata. Step 3 is what
	 * actually fires today (the observations API doesn't return metadata for generations). Steps 4-5 are the
	 * pre-existing behaviour, kept as a last resort so something always gets recorded.
	 */
	private static String resolveDatasetName(ObservationV2 observation, Function<String, Optional<ObservationV2>> parentLookup) {
		// Materialise once: the ancestor list is scanned twice below (metadata, then names)
		var ancestors = ancestorsOf(observation, parentLookup).toList();

		return datasetNameFromMetadata(observation)
			.or(() -> ancestors.stream()
				.map(ConversationExchange::datasetNameFromMetadata)
				.flatMap(Optional::stream)
				.findFirst())
			.or(() -> ancestors.stream()
				.map(ObservationV2::getName)
				.flatMap(name -> aiServiceSpanName(name).stream())
				.findFirst())
			.or(() -> nonBlank(observation.getTraceName()))
			.or(() -> nonBlank(observation.getName()))
			.orElse(DEFAULT_NAME);
	}

	/**
	 * Lazily walks up the parent chain starting from (and excluding) {@code observation}, nearest ancestor first.
	 */
	private static Stream<ObservationV2> ancestorsOf(ObservationV2 observation, Function<String, Optional<ObservationV2>> parentLookup) {
		var visited = new HashSet<String>();

		// Stream.iterate(seed, hasNext, next) - stops as soon as a parent lookup comes back empty (unknown id or reached the root).
		// The visited-set guard is belt-and-braces against a malformed cycle in the parent ids, which would otherwise loop forever.
		return Stream.iterate(
				parentLookup.apply(observation.getParentObservationId()),
				Optional::isPresent,
				parent -> parent.flatMap(p -> parentLookup.apply(p.getParentObservationId()))
			)
			.flatMap(Optional::stream)
			.takeWhile(parent -> visited.add(parent.getId()));
	}

	private static Optional<String> datasetNameFromMetadata(ObservationV2 observation) {
		// getMetadata() is typed as Object; in practice it's a Map when present, anything else is treated as absent
		return Optional.ofNullable(observation.getMetadata())
			.filter(Map.class::isInstance)
			.map(Map.class::cast)
			.flatMap(ConversationExchange::datasetNameFromMetadata);
	}

	/**
	 * Tries every shape the cascaded attributes might take in the metadata (see the comment on {@code ATTRIBUTES_KEY}).
	 * Prefers the ready-made dataset name; falls back to recomposing it from class + method if only those were captured.
	 */
	private static Optional<String> datasetNameFromMetadata(Map<?, ?> metadata) {
		var nestedAttributes = Optional.ofNullable(metadata.get(ATTRIBUTES_KEY))
			.filter(Map.class::isInstance)
			.map(Map.class::cast);

		return stringValue(metadata, DATASET_NAME_KEY)
			.or(() -> stringValue(metadata, ATTRIBUTES_PREFIX + DATASET_NAME_KEY))
			.or(() -> nestedAttributes.flatMap(attrs -> stringValue(attrs, DATASET_NAME_KEY)))
			.or(() -> composeDatasetName(stringValue(metadata, AI_SERVICE_CLASS_KEY), stringValue(metadata, AI_SERVICE_METHOD_KEY)))
			.or(() -> composeDatasetName(stringValue(metadata, ATTRIBUTES_PREFIX + AI_SERVICE_CLASS_KEY), stringValue(metadata, ATTRIBUTES_PREFIX + AI_SERVICE_METHOD_KEY)))
			.or(() -> nestedAttributes.flatMap(attrs -> composeDatasetName(stringValue(attrs, AI_SERVICE_CLASS_KEY), stringValue(attrs, AI_SERVICE_METHOD_KEY))));
	}

	/**
	 * Rebuilds the AI service span name from its class & method coordinates, for when only those were captured.
	 * Must produce exactly what {@code AiServiceDatasetSpanProcessor} would have put in {@code langfuse.dataset.name}
	 * so the exchange lands in the same dataset either way.
	 */
	private static Optional<String> composeDatasetName(Optional<String> serviceClass, Optional<String> serviceMethod) {
		return serviceClass.flatMap(clazz -> serviceMethod.map(method -> "%s%s.%s".formatted(AI_SERVICES_PREFIX, clazz, method)));
	}

	/** Type- and blank-safe read of a string entry from an untyped map. */
	private static Optional<String> stringValue(Map<?, ?> map, String key) {
		return Optional.ofNullable(map.get(key))
			.filter(String.class::isInstance)
			.map(String.class::cast)
			.filter(value -> !value.isBlank());
	}

	/** Only AI service spans qualify as dataset names; other ancestors (HTTP spans, tool spans, etc.) are skipped. */
	private static Optional<String> aiServiceSpanName(String spanName) {
		return nonBlank(spanName)
			.filter(name -> name.startsWith(AI_SERVICES_PREFIX));
	}

	private static Optional<String> nonBlank(String value) {
		return Optional.ofNullable(value).filter(v -> !v.isBlank());
	}
}
