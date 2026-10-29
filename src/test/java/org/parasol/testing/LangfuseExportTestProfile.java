package org.parasol.testing;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@link StubbedModelsTestProfile}, plus spans exported to the Langfuse dev service and the session scoring that reads them
 * back, for the tests of what Langfuse ingested. Share it rather than adding another: every profile class is another full
 * application boot.
 * <ul>
 *   <li><b>Session scoring, dataset creation and startup initialization on:</b> what the session scoring test exercises. The
 *   stub Gemini key makes the initializer's evaluator fail validation, which it logs and survives.</li>
 *   <li><b>{@code session-sentiment} and {@code judge} on WireMock</b>, for the session scoring call.</li>
 *   <li><b>OTLP export on, LGTM off:</b> nothing reads Tempo, Loki or Prometheus here.</li>
 * </ul>
 */
public class LangfuseExportTestProfile extends StubbedModelsTestProfile {
	private static final String WIREMOCK_URL = "http://localhost:${quarkus.wiremock.devservices.port}/v1";

	@Override
	public Map<String, String> getConfigOverrides() {
		var overrides = Map.ofEntries(
			Map.entry("quarkus.otel.exporter.otlp.enabled", "true"),
			Map.entry("quarkus.observability.enabled", "false"),
			Map.entry("quarkus.aiscoring.interaction-mode", "normal"),
			Map.entry("quarkus.aiscoring.langfuse.evaluation.initialize-on-startup", "true"),
			Map.entry("quarkus.aiscoring.langfuse.evaluation.gemini.api-key", "changeme"),
			Map.entry("quarkus.aiscoring.langfuse.evaluation.session.score-session", "true"),
			Map.entry("quarkus.aiscoring.langfuse.evaluation.session.create-dataset-on-session-close", "true"),
			Map.entry("quarkus.langchain4j.openai.session-sentiment.base-url", WIREMOCK_URL),
			Map.entry("quarkus.langchain4j.openai.judge.base-url", WIREMOCK_URL)
		);

		return Stream.concat(super.getConfigOverrides().entrySet().stream(), overrides.entrySet().stream())
			.collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
	}
}