package org.parasol.intake.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The two Grafana dashboards the app provisions in dev ({@code META-INF/grafana}) and on the cluster. Plain JUnit: nothing here boots the app.
 */
class GrafanaDashboardTests {
	private static final String INTAKE = "grafana-dashboard-claim-intake.json";
	private static final String AI = "grafana-dashboard-ai.json";
	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	void everyIntakeMeterIsChartedOnTheIntakeDashboard() throws IOException {
		var queries = queries(load(INTAKE));

		// The OTLP translation: dots become underscores, then a counter gets _total, a timer _milliseconds_*, a summary its unit and _bucket/_sum/_count
		var uncharted = IntakeMeterSchema.all().stream()
			.map(IntakeMeterSchema.MeterSchema::name)
			.filter(meter -> {
				var pattern = Pattern.compile(Pattern.quote(meter.replace('.', '_')) + "(_total|_milliseconds_\\w+|_bytes_\\w+|_seconds|_bucket|_sum|_count)?(?![A-Za-z0-9_])");
				return queries.stream().noneMatch(query -> pattern.matcher(query).find());
			})
			.toList();

		assertThat(uncharted).as("claim.intake.* meters with no panel").isEmpty();
	}

	@Test
	void everyPanelReadsPrometheusByItsUid() throws IOException {
		for (var file : List.of(INTAKE, AI)) {
			var datasources = new ArrayList<JsonNode>();
			panels(load(file)).forEach(panel -> {
				datasources.add(panel.path("datasource"));
				panel.path("targets").forEach(target -> datasources.add(target.path("datasource")));
			});

			assertThat(datasources).as(file).isNotEmpty().allSatisfy(datasource -> assertThat(datasource.path("uid").asText()).isEqualTo("prometheus"));
		}
	}

	@Test
	void panelIdsAreUniqueAndThereIsNoDashboardId() throws IOException {
		for (var file : List.of(INTAKE, AI)) {
			var dashboard = load(file);
			var ids = panels(dashboard).map(panel -> panel.path("id").asInt()).toList();

			assertThat(ids).as(file).doesNotHaveDuplicates().doesNotContain(0);
			assertThat(dashboard.has("id")).as(file + " has a top-level id").isFalse();
		}
	}

	@Test
	void theDashboardsLinkToEachOther() throws IOException {
		var intake = load(INTAKE);
		var ai = load(AI);

		assertThat(intake.path("uid").asText()).isEqualTo("parasol-claim-intake");
		assertThat(ai.path("uid").asText()).isEqualTo("parasol-ai");
		assertThat(urls(ai)).anyMatch(url -> url.startsWith("/d/parasol-claim-intake"));
		assertThat(urls(intake)).anyMatch(url -> url.startsWith("/d/parasol-ai?") && url.contains("var-ai_service=ClaimSummaryAgent"));
	}

	@Test
	void noQueryGroupsByALabelThatCouldIdentifyAClaimOrASender() throws IOException {
		var forbidden = List.of("claim_number", "email_address", "message_id", "conversation_id", "sender");

		for (var file : List.of(INTAKE, AI)) {
			assertThat(queries(load(file))).as(file).noneMatch(query -> forbidden.stream().anyMatch(query::contains));
		}
	}

	private static List<String> urls(JsonNode dashboard) {
		return stream(dashboard.path("links")).map(link -> link.path("url").asText()).toList();
	}

	private static List<String> queries(JsonNode dashboard) {
		return panels(dashboard).flatMap(panel -> stream(panel.path("targets"))).map(target -> target.path("expr").asText()).toList();
	}

	private static Stream<JsonNode> panels(JsonNode dashboard) {
		return stream(dashboard.path("panels")).filter(panel -> !panel.path("type").asText().equals("row"));
	}

	private static Stream<JsonNode> stream(JsonNode array) {
		return Stream.iterate(0, i -> i + 1).limit(array.size()).map(array::get);
	}

	private static JsonNode load(String file) throws IOException {
		try (var in = GrafanaDashboardTests.class.getResourceAsStream("/META-INF/grafana/" + file)) {
			assertThat(in).as(file).isNotNull();
			return MAPPER.readTree(in);
		}
	}
}
