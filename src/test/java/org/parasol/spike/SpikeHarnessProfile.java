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
		return Map.ofEntries(
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
}
