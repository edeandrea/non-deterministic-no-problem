package org.parasol.testing;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.parasol.intake.WorkflowSpans;
import org.parasol.testing.mail.ToxiproxyTestResource;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * The one profile for every test whose models are stubbed or never called: the intake tests, the tests of what the intake
 * stores, and the drift and Langfuse dataset tests. Stubbed API keys, no Easy RAG ingestion at boot, and the {@code claim-intake},
 * {@code parasol-chat} and default OpenAI models pointed at the WireMock dev service. Share it rather than adding another:
 * Quarkus restarts the application whenever the profile class changes, and each restart starts the Dev Services again
 * (Postgres, GreenMail, Langfuse, WireMock). A test whose config is additive belongs here. Intake itself stays disabled
 * ({@code %test}).
 * <ul>
 *   <li><b>Keys:</b> the stub keys mean no test needs a real key or a running model to start.</li>
 *   <li><b>Easy RAG ingestion off:</b> boot-time ingestion would call the embedding model with the stub key (a 401 from
 *   OpenAI, or {@code localhost:11434} under {@code -Pollama-openai}), or WireMock before a test's {@code @BeforeEach} stubs
 *   exist. Queries are still embedded at chat time, against an empty store, so a chat test stubs {@code /v1/embeddings}.</li>
 *   <li><b>{@code claim-intake} on WireMock, provider pinned to {@code openai}:</b> {@code %ollama} switches it to
 *   {@code ollama}, which would bypass the stubs and reach a real model. The model name is pinned too: {@code %ollama-openai}
 *   renames it, and the request body must be the same under every profile.</li>
 *   <li><b>{@code parasol-chat}, the default OpenAI model and embeddings on WireMock, providers pinned to {@code openai}:</b>
 *   for the chat route tests. The pins look redundant under the default profile; they are not: {@code %ollama} switches both
 *   to {@code ollama}, which would route the chat around the stubs and into a real model.</li>
 *   <li><b>Hibernate statistics on</b>, for the tests that assert what a request loads.</li>
 *   <li><b>Spans exported every 100 ms</b>, so {@link WorkflowSpans} sees a run's span soon after it ends.</li>
 *   <li><b>A Toxiproxy container</b> ({@link ToxiproxyTestResource}) for the tests that cut the watcher's connection. It's a
 *   test resource of the profile, so it starts once with the profile's one boot.</li>
 * </ul>
 */
public class StubbedModelsTestProfile implements QuarkusTestProfile {
	/**
	 * The model name every stubbed {@code claim-intake} request carries, under every profile.
	 */
	public static final String MODEL_NAME = "claim-intake-model";

	private static final String WIREMOCK_URL = "http://localhost:${quarkus.wiremock.devservices.port}/v1";

	/**
	 * The overrides that point only the {@code claim-intake} model at WireMock, for a profile that keeps the other models real.
	 *
	 * @return the {@code claim-intake} overrides
	 */
	public static Map<String, String> claimIntakeOnWireMock() {
		return Map.of(
			"quarkus.langchain4j.claim-intake.chat-model.provider", "openai",
			"quarkus.langchain4j.openai.claim-intake.api-key", "changeme",
			"quarkus.langchain4j.openai.claim-intake.base-url", WIREMOCK_URL,
			"quarkus.langchain4j.openai.claim-intake.chat-model.model-name", MODEL_NAME
		);
	}

	@Override
	public Map<String, String> getConfigOverrides() {
		var overrides = Map.ofEntries(
			Map.entry("quarkus.langchain4j.openai.api-key", "changeme"),
			Map.entry("quarkus.langchain4j.openai.base-url", WIREMOCK_URL),
			Map.entry("quarkus.langchain4j.openai.parasol-chat.api-key", "changeme"),
			Map.entry("quarkus.langchain4j.openai.parasol-chat.base-url", WIREMOCK_URL),
			Map.entry("quarkus.langchain4j.parasol-chat.chat-model.provider", "openai"),
			Map.entry("quarkus.langchain4j.embedding-model.provider", "openai"),
			Map.entry("quarkus.langchain4j.openai.session-sentiment.api-key", "changeme"),
			Map.entry("quarkus.langchain4j.openai.judge.api-key", "changeme"),
			Map.entry("quarkus.langchain4j.easy-rag.ingestion-strategy", "OFF"),
			Map.entry("quarkus.hibernate-orm.statistics", "true"),
			Map.entry("quarkus.otel.bsp.schedule.delay", "100ms")
		);

		return Stream.concat(overrides.entrySet().stream(), claimIntakeOnWireMock().entrySet().stream())
			.collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	@Override
	public List<TestResourceEntry> testResources() {
		return List.of(new TestResourceEntry(ToxiproxyTestResource.class));
	}
}