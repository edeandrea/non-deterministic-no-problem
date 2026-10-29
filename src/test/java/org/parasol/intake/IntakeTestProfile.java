package org.parasol.intake;

import java.util.List;
import java.util.Map;

import org.parasol.testing.mail.ToxiproxyTestResource;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * The one profile for every intake test, and the tests of what the intake stores: stubbed API keys, no Easy RAG ingestion at
 * boot, and the {@code claim-intake} model pointed at the WireMock dev service. Share it rather than adding another:
 * Quarkus restarts the application whenever the profile class changes, and each restart starts the Dev Services again
 * (Postgres, GreenMail, Langfuse, WireMock). Intake itself stays disabled ({@code %test}).
 * <ul>
 *   <li><b>Keys:</b> the stub keys mean no test needs a real key or a running model to start.</li>
 *   <li><b>Easy RAG ingestion off:</b> boot-time ingestion would call the embedding model with the stub key (a 401 from
 *   OpenAI, or {@code localhost:11434} under {@code -Pollama-openai}). Nothing here uses RAG.</li>
 *   <li><b>{@code claim-intake} on WireMock, provider pinned to {@code openai}:</b> {@code %ollama} switches it to
 *   {@code ollama}, which would bypass the stubs and reach a real model. The model name is pinned too: {@code %ollama-openai}
 *   renames it, and the request body must be the same under every profile.</li>
 *   <li><b>Hibernate statistics on</b>, for the tests that assert what a request loads.</li>
 *   <li><b>Spans exported every 100 ms</b>, so {@link TestSpans} sees a run's spans soon after they end.</li>
 *   <li><b>A Toxiproxy container</b> ({@link ToxiproxyTestResource}) for the tests that cut the watcher's connection. It's a
 *   test resource of the profile, so it starts once with the profile's one boot.</li>
 * </ul>
 */
public class IntakeTestProfile implements QuarkusTestProfile {
	/**
	 * The model name every stubbed {@code claim-intake} request carries, under every profile.
	 */
	public static final String MODEL_NAME = "claim-intake-model";

	private static final String WIREMOCK_URL = "http://localhost:${quarkus.wiremock.devservices.port}/v1";

	@Override
	public Map<String, String> getConfigOverrides() {
		return Map.ofEntries(
			Map.entry("quarkus.langchain4j.openai.api-key", "changeme"),
			Map.entry("quarkus.langchain4j.openai.session-sentiment.api-key", "changeme"),
			Map.entry("quarkus.langchain4j.openai.judge.api-key", "changeme"),
			Map.entry("quarkus.langchain4j.easy-rag.ingestion-strategy", "OFF"),
			Map.entry("quarkus.langchain4j.claim-intake.chat-model.provider", "openai"),
			Map.entry("quarkus.langchain4j.openai.claim-intake.api-key", "changeme"),
			Map.entry("quarkus.langchain4j.openai.claim-intake.base-url", WIREMOCK_URL),
			Map.entry("quarkus.langchain4j.openai.claim-intake.chat-model.model-name", MODEL_NAME),
			Map.entry("quarkus.hibernate-orm.statistics", "true"),
			Map.entry("quarkus.otel.bsp.schedule.delay", "100ms")
		);
	}

	@Override
	public List<TestResourceEntry> testResources() {
		return List.of(new TestResourceEntry(ToxiproxyTestResource.class));
	}
}