package org.parasol.spike;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * #221 spike: the real claim-intake LLM (as the active Maven profile defines it) and the real decision model, on
 * Ollama. Never part of a normal test run: {@link DecisionSpikeHarnessTests} only runs with {@code -Dspike=true}.
 */
public class SpikeHarnessProfile implements QuarkusTestProfile {
	@Override
	public Map<String, String> getConfigOverrides() {
		var overrides = new java.util.HashMap<>(OVERRIDES);
		// Hosted Jev for the configured agents: -Dparasol.models.decision-base-url=https://api.typesafe.ai
		// -Dparasol.models.decision-model-id=jev-1.14.0, with the key in TYPESAFE_API_KEY (never in a file)
		java.util.Optional.ofNullable(System.getenv("TYPESAFE_API_KEY"))
			.filter(key -> !key.isBlank())
			.ifPresent(_ -> overrides.put("quarkus.langchain4j.typesafe.claim-decisions.api-key", "${TYPESAFE_API_KEY}"));
		return overrides;
	}

	private static final Map<String, String> OVERRIDES = Map.ofEntries(
		Map.entry("quarkus.langchain4j.claim-intake.chat-model.provider", "${parasol.models.provider}"),
		Map.entry("quarkus.langchain4j.openai.claim-intake.base-url", "${parasol.models.base-url}"),
		Map.entry("quarkus.langchain4j.openai.claim-intake.chat-model.model-name", "${parasol.models.ollama.chat-model-id}"),
		Map.entry("quarkus.langchain4j.openai.claim-intake.timeout", "600s"),
		Map.entry("quarkus.langchain4j.typesafe.claim-decisions.base-url", "${parasol.models.decision-base-url}"),
		Map.entry("quarkus.langchain4j.typesafe.claim-decisions.decision-model.model-name", "${parasol.models.decision-model-id}"),
		Map.entry("quarkus.langchain4j.log-requests", "false"),
		Map.entry("quarkus.langchain4j.log-responses", "false"),
		Map.entry("quarkus.langfuse.devservices.enabled", "false"),
		Map.entry("quarkus.langfuse.base-url", "http://localhost:0"),
		Map.entry("quarkus.langfuse.public-key", "changeme"),
		Map.entry("quarkus.langfuse.secret-key", "changeme"));
}
