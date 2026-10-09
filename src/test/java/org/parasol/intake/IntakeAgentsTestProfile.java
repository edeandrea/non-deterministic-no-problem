package org.parasol.intake;

import java.util.HashMap;
import java.util.Map;

/**
 * The profile for the intake agent tests: {@link IntakeTestProfile} plus the {@code claim-intake} model pointed at the
 * WireMock dev service. Shared, so every agent test class boots the same app.
 */
public class IntakeAgentsTestProfile extends IntakeTestProfile {
	private static final String WIREMOCK_URL = "http://localhost:${quarkus.wiremock.devservices.port}/v1";

	/**
	 * The model name every stubbed request carries, under every profile.
	 */
	public static final String MODEL_NAME = "claim-intake-model";

	@Override
	public Map<String, String> getConfigOverrides() {
		var overrides = new HashMap<>(super.getConfigOverrides());
		// Pin the provider: %ollama switches claim-intake to ollama, which would bypass the WireMock stubs
		overrides.put("quarkus.langchain4j.claim-intake.chat-model.provider", "openai");
		overrides.put("quarkus.langchain4j.openai.claim-intake.api-key", "changeme");
		overrides.put("quarkus.langchain4j.openai.claim-intake.base-url", WIREMOCK_URL);
		// %ollama-openai renames the model; keep the request body the same under every profile
		overrides.put("quarkus.langchain4j.openai.claim-intake.chat-model.model-name", MODEL_NAME);
		return Map.copyOf(overrides);
	}
}
