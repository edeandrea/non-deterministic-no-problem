package org.parasol.ui;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.quarkus.test.junit.QuarkusTestProfile;

import io.quarkiverse.quinoa.testing.QuinoaTestProfiles;

/**
 * The one profile for every Playwright test: Quinoa's own test-build-and-run-Jest flags
 * ({@link QuinoaTestProfiles.EnableAndRunTests}), plus un-stubbing {@code parasol-chat} and the embedding model back to
 * real, with real Easy RAG ingestion at boot, because the claim detail page's chat test talks to the real chat bot.
 * {@code claim-intake} needs no override here: {@code %test} already pins it on WireMock exactly as this profile used
 * to, for the review panel, whose claim only reaches {@code Pending Review} through a real {@code ClaimIntakeFlow} run.
 * Share this profile rather than adding another: every profile class is another full application boot.
 * <p>
 *   <b>C1 precedence gotcha:</b> {@code %test} wins over {@code %ollama} / {@code %ollama-openai} (multiple profiles
 *   resolve right-to-left), so this can't hard-code {@code openai} / {@code https://api.openai.com/v1} - that would be
 *   wrong under {@code -Pollama} and {@code -Pollama-openai}. Instead it reads back {@code parasol.models.*}, the
 *   indirection the Maven profiles own (see {@code application.yml}), to recover "the real model" whichever Maven
 *   profile is active.
 * </p>
 * <p>
 *   It also starts Roundcube, the {@code webmail} profile of compose-devservices.yml, for the Roundcube end-to-end test,
 *   on a random host port ({@code ROUNDCUBE_PORT=0}) mapped into {@code parasol.mail.roundcube-port}, so it never clashes
 *   with dev mode's 8000. This override wins over the {@code quarkus.compose.devservices.profiles=none} that surefire and
 *   failsafe set for every other test. Every Playwright boot starts it, which costs a few seconds each.
 * </p>
 * <p>
 *   <b>The Langfuse Dev Service is disabled here:</b> no Playwright test references {@code LangfuseOperations} or any
 *   Langfuse API, and {@code %test} already turns off {@code initialize-on-startup}/{@code score-session}/OTel export, so
 *   nothing needs a live connection. Without this override the Dev Service's six-container stack (Postgres, ClickHouse,
 *   Redis, MinIO, Langfuse, Langfuse-worker) still started on this boot for no reason, costing ~20-30s.
 * </p>
 */
public class UiTestProfile implements QuarkusTestProfile {
	private static final Map<String, String> WEBMAIL = Map.of(
		"quarkus.compose.devservices.profiles", "webmail",
		"quarkus.compose.devservices.env-variables.ROUNDCUBE_PORT", "0"
	);

	private static final Map<String, String> REAL_MODELS = Map.of(
		"quarkus.langchain4j.parasol-chat.chat-model.provider", "${parasol.models.provider}",
		"quarkus.langchain4j.embedding-model.provider", "${parasol.models.provider}",
		"quarkus.langchain4j.openai.base-url", "${parasol.models.base-url}",
		"quarkus.langchain4j.openai.parasol-chat.base-url", "${parasol.models.base-url}",
		"quarkus.langchain4j.openai.parasol-chat.api-key", "${quarkus.langchain4j.openai.api-key}",
		"quarkus.langchain4j.easy-rag.ingestion-strategy", "ON"
	);

	private static final Map<String, String> NO_LANGFUSE = Map.of(
		"quarkus.langfuse.devservices.enabled", "false"
	);

	@Override
	public Map<String, String> getConfigOverrides() {
		return Stream.of(
				new QuinoaTestProfiles.EnableAndRunTests().getConfigOverrides(),
				REAL_MODELS,
				WEBMAIL,
				NO_LANGFUSE)
			.flatMap(overrides -> overrides.entrySet().stream())
			.collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
	}
}
