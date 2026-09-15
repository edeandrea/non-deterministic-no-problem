package ai.scoring.langfuse.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.langfuse.api.model.ObservationV2;

import ai.scoring.langfuse.otel.AiServiceAttributes;

class ConversationExchangeTests {
	private static final String SERVICE_CLASS = "AiServiceClassName";
	private static final String METHOD_NAME = "methodName";
	private static final String DATASET_NAME = "%s%s.%s".formatted(AiServiceAttributes.AI_SERVICES_PREFIX, SERVICE_CLASS, METHOD_NAME);
	private static final String PACKAGE_QUALIFIED_DATASET_NAME = "%sorg.acme.%s.%s".formatted(AiServiceAttributes.AI_SERVICES_PREFIX, SERVICE_CLASS, METHOD_NAME);
	private static final String OTHER_DATASET_NAME = "%sOtherAiService.otherMethod".formatted(AiServiceAttributes.AI_SERVICES_PREFIX);

	// Realistic-looking names for spans that are NOT AI service spans, and so must never be picked as a dataset name
	private static final String GENERATION_NAME = "completion some-model";
	private static final String TOOL_SPAN_NAME = "execute_tool someTool";
	private static final String HTTP_SPAN_NAME = "HTTP POST /some/endpoint";

	@Test
	void directMetadataAttributeExtractsDatasetName() {
		var observation = ObservationV2.builder()
			.name(GENERATION_NAME)
			.traceId("trace-1")
			.traceName(DATASET_NAME)
			.input("hello")
			.output("world")
			.metadata(Map.of("langfuse.dataset.name", DATASET_NAME))
			.build();

		var exchange = ConversationExchange.from(observation);

		assertThat(exchange)
			.isEqualTo(new ConversationExchange(DATASET_NAME, DATASET_NAME, "trace-1", "hello", "world"));
	}

	@Test
	void prefixedMetadataAttributeExtractsDatasetName() {
		var observation = ObservationV2.builder()
			.name(GENERATION_NAME)
			.traceId("trace-2")
			.metadata(Map.of("attributes.langfuse.dataset.name", DATASET_NAME))
			.build();

		assertThat(ConversationExchange.from(observation).datasetName())
			.isEqualTo(DATASET_NAME);
	}

	@Test
	void nestedAttributesExtractsDatasetName() {
		var observation = ObservationV2.builder()
			.name(GENERATION_NAME)
			.traceId("trace-2")
			.metadata(Map.of("attributes", Map.of("langfuse.dataset.name", DATASET_NAME)))
			.build();

		assertThat(ConversationExchange.from(observation).datasetName())
			.isEqualTo(DATASET_NAME);
	}

	@Test
	void serviceCoordinatesInMetadataComposeDatasetName() {
		var observation = ObservationV2.builder()
			.name(GENERATION_NAME)
			.traceId("trace-3")
			.metadata(Map.of("ai.service.class", SERVICE_CLASS, "ai.service.method", METHOD_NAME))
			.build();

		assertThat(ConversationExchange.from(observation).datasetName())
			.isEqualTo(DATASET_NAME);
	}

	@Test
	void serviceCoordinatesInNestedAttributesComposeDatasetName() {
		var observation = ObservationV2.builder()
			.name(GENERATION_NAME)
			.traceId("trace-4")
			.metadata(Map.of("attributes", Map.of("ai.service.class", SERVICE_CLASS, "ai.service.method", METHOD_NAME)))
			.build();

		assertThat(ConversationExchange.from(observation).datasetName())
			.isEqualTo(DATASET_NAME);
	}

	@Test
	void traceNameIsUsedVerbatimWhenNoMetadataOrAncestors() {
		var observation = ObservationV2.builder()
			.name(GENERATION_NAME)
			.traceId("trace-5")
			.traceName(DATASET_NAME)
			.build();

		var exchange = ConversationExchange.from(observation);

		assertThat(exchange.datasetName())
			.isEqualTo(DATASET_NAME);
		assertThat(exchange.traceName())
			.isEqualTo(DATASET_NAME);
	}

	@Test
	void fallbackToObservationName() {
		var observation = ObservationV2.builder()
			.name("default-span")
			.traceId("trace-7")
			.build();

		var exchange = ConversationExchange.from(observation);

		assertThat(exchange.datasetName())
			.isEqualTo("default-span");
		assertThat(exchange.traceName())
			.isEqualTo("default-span");
	}

	@Test
	void directParentAiServiceSpanNameIsUsedVerbatim() {
		var parent = ObservationV2.builder()
			.id("parent")
			.name(DATASET_NAME)
			.traceId("trace-8")
			.build();
		var generation = ObservationV2.builder()
			.id("generation")
			.parentObservationId("parent")
			.name(GENERATION_NAME)
			.traceId("trace-8")
			.build();

		var exchange = ConversationExchange.from(generation, ConversationExchange.hierarchyOf(List.of(parent, generation)));

		assertThat(exchange.datasetName())
			.isEqualTo(DATASET_NAME);
	}

	@Test
	void packageQualifiedAncestorNameIsUsedVerbatim() {
		var parent = ObservationV2.builder()
			.id("parent")
			.name(PACKAGE_QUALIFIED_DATASET_NAME)
			.traceId("trace-8b")
			.build();
		var generation = ObservationV2.builder()
			.id("generation")
			.parentObservationId("parent")
			.name(GENERATION_NAME)
			.traceId("trace-8b")
			.build();

		var exchange = ConversationExchange.from(generation, ConversationExchange.hierarchyOf(List.of(parent, generation)));

		assertThat(exchange.datasetName())
			.isEqualTo(PACKAGE_QUALIFIED_DATASET_NAME);
	}

	@Test
	void ancestorSeveralLevelsUpResolvesDatasetName() {
		// root (AI service) -> tool -> generation. The generation's *immediate* parent isn't an AI service span,
		// so resolution has to keep walking. Also passes the list out of order to prove hierarchyOf doesn't care.
		var root = ObservationV2.builder()
			.id("root")
			.name(DATASET_NAME)
			.traceId("trace-9")
			.build();
		var tool = ObservationV2.builder()
			.id("tool")
			.parentObservationId("root")
			.name(TOOL_SPAN_NAME)
			.traceId("trace-9")
			.build();
		var generation = ObservationV2.builder()
			.id("generation")
			.parentObservationId("tool")
			.name(GENERATION_NAME)
			.traceId("trace-9")
			.build();

		var exchange = ConversationExchange.from(generation, ConversationExchange.hierarchyOf(List.of(generation, tool, root)));

		assertThat(exchange.datasetName())
			.isEqualTo(DATASET_NAME);
	}

	@Test
	void ancestorMetadataTakesPrecedenceOverAncestorName() {
		var parent = ObservationV2.builder()
			.id("parent")
			.name("some-other-span")
			.traceId("trace-10")
			.metadata(Map.of("langfuse.dataset.name", OTHER_DATASET_NAME))
			.build();
		var generation = ObservationV2.builder()
			.id("generation")
			.parentObservationId("parent")
			.name(GENERATION_NAME)
			.traceId("trace-10")
			.build();

		var exchange = ConversationExchange.from(generation, ConversationExchange.hierarchyOf(List.of(parent, generation)));

		assertThat(exchange.datasetName())
			.isEqualTo(OTHER_DATASET_NAME);
	}

	@Test
	void ownMetadataTakesPrecedenceOverHierarchy() {
		var parent = ObservationV2.builder()
			.id("parent")
			.name(OTHER_DATASET_NAME)
			.traceId("trace-11")
			.build();
		var generation = ObservationV2.builder()
			.id("generation")
			.parentObservationId("parent")
			.name(GENERATION_NAME)
			.traceId("trace-11")
			.metadata(Map.of("langfuse.dataset.name", DATASET_NAME))
			.build();

		var exchange = ConversationExchange.from(generation, ConversationExchange.hierarchyOf(List.of(parent, generation)));

		assertThat(exchange.datasetName())
			.isEqualTo(DATASET_NAME);
	}

	@Test
	void nonAiServiceAncestorsAreIgnored() {
		var parent = ObservationV2.builder()
			.id("parent")
			.name(HTTP_SPAN_NAME)
			.traceId("trace-12")
			.build();
		var generation = ObservationV2.builder()
			.id("generation")
			.parentObservationId("parent")
			.name(GENERATION_NAME)
			.traceId("trace-12")
			.build();

		var exchange = ConversationExchange.from(generation, ConversationExchange.hierarchyOf(List.of(parent, generation)));

		assertThat(exchange.datasetName())
			.isEqualTo(GENERATION_NAME);
	}

	@Test
	void unknownParentFallsBackToObservationName() {
		var generation = ObservationV2.builder()
			.id("generation")
			.parentObservationId("missing")
			.name(GENERATION_NAME)
			.traceId("trace-13")
			.build();

		var exchange = ConversationExchange.from(generation, ConversationExchange.hierarchyOf(List.of(generation)));

		assertThat(exchange.datasetName())
			.isEqualTo(GENERATION_NAME);
	}

	@Test
	void cyclicHierarchyTerminates() {
		// a -> b -> a. Should never happen with real Langfuse data, but the walk must not spin forever if it does.
		var a = ObservationV2.builder()
			.id("a")
			.parentObservationId("b")
			.name("span-a")
			.traceId("trace-14")
			.build();
		var b = ObservationV2.builder()
			.id("b")
			.parentObservationId("a")
			.name("span-b")
			.traceId("trace-14")
			.build();
		var generation = ObservationV2.builder()
			.id("generation")
			.parentObservationId("a")
			.name(GENERATION_NAME)
			.traceId("trace-14")
			.build();

		var exchange = ConversationExchange.from(generation, ConversationExchange.hierarchyOf(List.of(a, b, generation)));

		assertThat(exchange.datasetName())
			.isEqualTo(GENERATION_NAME);
	}
}
