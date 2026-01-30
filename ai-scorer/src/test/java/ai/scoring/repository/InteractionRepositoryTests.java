package ai.scoring.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import ai.scoring.domain.interaction.Interaction;
import ai.scoring.domain.interaction.InteractionQuery;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@TestTransaction
class InteractionRepositoryTests {
	@Inject
	InteractionRepository interactionRepository;

	@ParameterizedTest
	@MethodSource("queries")
	void findInteractions(InteractionQuery query, List<Interaction> expectedInteractions) {
		// Setup data
		setupData();

		// Execute
		var results = interactionRepository.findInteractions(query);

		// Verify
		assertThat(results)
			.as("Query: %s", query)
			.hasSameSizeAs(expectedInteractions)
			.zipSatisfy(expectedInteractions, (actual, expected) ->
				assertThat(actual)
					.usingRecursiveComparison()
					.ignoringFieldsMatchingRegexes(".*interactionId", ".*scores", ".*hibernate_.*")
					.isEqualTo(expected)
			);
	}

	private void setupData() {
		interactionRepository.deleteAll();

		var interaction1 = Interaction.builder()
			.interactionId(UUID.randomUUID())
			.applicationName("testApp")
			.interfaceName("testInterface")
			.methodName("testMethod")
			.interactionDate(Instant.parse("2024-01-01T12:00:00Z"))
			.systemMessage("sys")
			.userMessage("user")
			.result("res")
			.build();

		interactionRepository.persist(interaction1);

		// Another interaction with different values to ensure filtering works
		var interaction2 = Interaction.builder()
			.interactionId(UUID.randomUUID())
			.applicationName("otherApp")
			.interfaceName("otherInterface")
			.methodName("otherMethod")
			.interactionDate(Instant.parse("2024-02-01T12:00:00Z"))
			.systemMessage("sys")
			.userMessage("user")
			.result("res")
			.build();

		interactionRepository.persist(interaction2);
	}

	static Stream<Arguments> queries() {
		var app = "testApp";
		var iface = "testInterface";
		var method = "testMethod";
		var start = Instant.parse("2024-01-01T00:00:00Z");
		var end = Instant.parse("2024-01-02T00:00:00Z");

		var i1 = Interaction.builder()
			.interactionId(UUID.randomUUID())
			.applicationName("testApp")
			.interfaceName("testInterface")
			.methodName("testMethod")
			.interactionDate(Instant.parse("2024-01-01T12:00:00Z"))
			.systemMessage("sys")
			.userMessage("user")
			.result("res")
			.build();

		var i2 = Interaction.builder()
			.interactionId(UUID.randomUUID())
			.applicationName("otherApp")
			.interfaceName("otherInterface")
			.methodName("otherMethod")
			.interactionDate(Instant.parse("2024-02-01T12:00:00Z"))
			.systemMessage("sys")
			.userMessage("user")
			.result("res")
			.build();

		return Stream.of(
			// 0 fields
			Arguments.of(InteractionQuery.builder().build(), List.of(i1, i2)),

			// 1 field
			Arguments.of(InteractionQuery.builder().applicationName(app).build(), List.of(i1)),
			Arguments.of(InteractionQuery.builder().interfaceName(iface).build(), List.of(i1)),
			Arguments.of(InteractionQuery.builder().methodName(method).build(), List.of(i1)),
			Arguments.of(InteractionQuery.builder().start(start).build(), List.of(i1, i2)),
			Arguments.of(InteractionQuery.builder().end(end).build(), List.of(i1)),

			// 2 fields
			Arguments.of(InteractionQuery.builder().applicationName(app).interfaceName(iface).build(), List.of(i1)),
			Arguments.of(InteractionQuery.builder().applicationName(app).methodName(method).build(), List.of(i1)),
			Arguments.of(InteractionQuery.builder().applicationName(app).start(start).build(), List.of(i1)),
			Arguments.of(InteractionQuery.builder().applicationName(app).end(end).build(), List.of(i1)),
			Arguments.of(InteractionQuery.builder().interfaceName(iface).methodName(method).build(), List.of(i1)),
			Arguments.of(InteractionQuery.builder().interfaceName(iface).start(start).build(), List.of(i1)),
			Arguments.of(InteractionQuery.builder().interfaceName(iface).end(end).build(), List.of(i1)),
			Arguments.of(InteractionQuery.builder().methodName(method).start(start).build(), List.of(i1)),
			Arguments.of(InteractionQuery.builder().methodName(method).end(end).build(), List.of(i1)),
			Arguments.of(InteractionQuery.builder().start(start).end(end).build(), List.of(i1)),

			// 3 fields
			Arguments.of(InteractionQuery.builder().applicationName(app).interfaceName(iface).methodName(method).build(), List.of(i1)),
			Arguments.of(InteractionQuery.builder().applicationName(app).interfaceName(iface).start(start).build(), List.of(i1)),
			Arguments.of(InteractionQuery.builder().applicationName(app).interfaceName(iface).end(end).build(), List.of(i1)),
			Arguments.of(InteractionQuery.builder().applicationName(app).methodName(method).start(start).build(), List.of(i1)),
			Arguments.of(InteractionQuery.builder().applicationName(app).methodName(method).end(end).build(), List.of(i1)),
			Arguments.of(InteractionQuery.builder().applicationName(app).start(start).end(end).build(), List.of(i1)),
			Arguments.of(InteractionQuery.builder().interfaceName(iface).methodName(method).start(start).build(), List.of(i1)),
			Arguments.of(InteractionQuery.builder().interfaceName(iface).methodName(method).end(end).build(), List.of(i1)),
			Arguments.of(InteractionQuery.builder().interfaceName(iface).start(start).end(end).build(), List.of(i1)),
			Arguments.of(InteractionQuery.builder().methodName(method).start(start).end(end).build(), List.of(i1)),

			// 4 fields
			Arguments.of(InteractionQuery.builder().applicationName(app).interfaceName(iface).methodName(method).start(start).build(), List.of(i1)),
			Arguments.of(InteractionQuery.builder().applicationName(app).interfaceName(iface).methodName(method).end(end).build(), List.of(i1)),
			Arguments.of(InteractionQuery.builder().applicationName(app).interfaceName(iface).start(start).end(end).build(), List.of(i1)),
			Arguments.of(InteractionQuery.builder().applicationName(app).methodName(method).start(start).end(end).build(), List.of(i1)),
			Arguments.of(InteractionQuery.builder().interfaceName(iface).methodName(method).start(start).end(end).build(), List.of(i1)),

			// 5 fields
			Arguments.of(InteractionQuery.builder().applicationName(app).interfaceName(iface).methodName(method).start(start).end(end).build(), List.of(i1)),

			// Mismatched values (expect 0)
			Arguments.of(InteractionQuery.builder().applicationName("wrong").build(), List.of()),
			Arguments.of(InteractionQuery.builder().interfaceName("wrong").build(), List.of()),
			Arguments.of(InteractionQuery.builder().methodName("wrong").build(), List.of()),
			Arguments.of(InteractionQuery.builder().start(Instant.parse("2024-03-01T00:00:00Z")).build(), List.of()),
			Arguments.of(InteractionQuery.builder().end(Instant.parse("2023-12-01T00:00:00Z")).build(), List.of())
		);
	}
}