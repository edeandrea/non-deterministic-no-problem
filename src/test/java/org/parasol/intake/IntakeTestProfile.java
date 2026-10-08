package org.parasol.intake;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * The base profile for intake tests: stubbed API keys and no Easy RAG ingestion at boot, so no test needs a real key
 * or a running model to start. Intake itself stays disabled ({@code %test}).
 */
public class IntakeTestProfile implements QuarkusTestProfile {
	@Override
	public Map<String, String> getConfigOverrides() {
		return Map.of(
			"quarkus.langchain4j.openai.api-key", "changeme",
			"quarkus.langchain4j.openai.session-sentiment.api-key", "changeme",
			"quarkus.langchain4j.openai.judge.api-key", "changeme",
			// Boot-time ingestion would call the embedding model with the stub key (a 401 from OpenAI, or localhost:11434
			// under -Pollama-openai). Nothing here uses RAG
			"quarkus.langchain4j.easy-rag.ingestion-strategy", "OFF"
		);
	}
}