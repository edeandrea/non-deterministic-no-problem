package ai.scoring.it;

import static io.quarkiverse.langchain4j.testing.evaluation.EvaluationAssertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.websockets.next.BasicWebSocketConnector;

import ai.scoring.langfuse.evaluation.Evaluator;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.quarkiverse.langchain4j.chatscopes.websocket.WebsocketChatRoutes;
import io.quarkiverse.langchain4j.evaluation.junit5.Evaluate;
import io.quarkiverse.langchain4j.evaluation.junit5.SampleLocation;
import io.quarkiverse.langchain4j.evaluation.junit5.ScorerConfiguration;
import io.quarkiverse.langchain4j.testing.evaluation.EvaluationStrategy;
import io.quarkiverse.langchain4j.testing.evaluation.Parameters;
import io.quarkiverse.langchain4j.testing.evaluation.Samples;
import io.quarkiverse.langchain4j.testing.evaluation.Scorer;
import io.quarkiverse.langchain4j.testing.evaluation.similarity.SemanticSimilarityStrategy;

/**
 * SPIKE (throwaway): replays the live Langfuse dataset that chat sessions recorded through the real chat route, and
 * compares the result with an accepted baseline using quarkus-langchain4j#3227's {@code isNotWorseThanBaseline}.
 */
@QuarkusTest
@TestProfile(BaselineSpikeTests.SpikeProfile.class)
@Evaluate
class BaselineSpikeTests {
	record Query(QueryData query) {
		record QueryData(long claimId, String claim, String query, LocalDate inceptionDate) {}
	}

	@ConfigProperty(name = "quarkus.http.test-port")
	int testPort;

	@Inject
	Evaluator evaluator;

	@Inject
	EmbeddingModel embeddingModel;

	@Test
	void claimChatIsNotWorseThanBaseline(
		@ScorerConfiguration(concurrency = 1, trials = 2) Scorer scorer,
		@SampleLocation("langchain4j.aiservices.ClaimService.chat") Samples<String> samples) {

		Log.infof("Loaded %d live samples from Langfuse", samples.size());

		var report = scorer.evaluate(
			samples,
			this::replay,
			// local, deterministic: blocks
			new SemanticSimilarityStrategy(this.embeddingModel, 0.80),
			// our LLM judge (EvaluatorAgent on Ollama): reported and compared, never fails the test
			EvaluationStrategy.toAdvisory(this.evaluator));

		assertThat(report).isNotWorseThanBaseline("ClaimService.chat");
	}

	private String replay(Parameters parameters) {
		var answer = new CompletableFuture<String>();
		var connector = BasicWebSocketConnector.create()
		                                       .baseUri("http://localhost:%d/_chat/routes".formatted(this.testPort));
		var builder = WebsocketChatRoutes.newClient(connector)
		                                 .builder()
		                                 .messageHandler(answer::complete)
		                                 .errorHandler(error -> answer.completeExceptionally(new IllegalStateException(error)));

		try (var session = builder.connect("chat")) {
			session.chat(new Query(new Query.QueryData(
				Long.parseLong(parameters.get("claimId")),
				parameters.get("claim"),
				parameters.get("query"),
				LocalDate.parse(parameters.get("inceptionDate")))));

			return answer.get(5, TimeUnit.MINUTES);
		}
		catch (Exception e) {
			throw new IllegalStateException("Replay failed", e);
		}
	}

	public static class SpikeProfile implements QuarkusTestProfile {
		@Override
		public Map<String, String> getConfigOverrides() {
			return Map.ofEntries(
				// the real chat model and embeddings, as UiTestProfile does
				Map.entry("quarkus.langchain4j.parasol-chat.chat-model.provider", "${parasol.models.provider}"),
				Map.entry("quarkus.langchain4j.embedding-model.provider", "${parasol.models.provider}"),
				Map.entry("quarkus.langchain4j.openai.base-url", "${parasol.models.base-url}"),
				Map.entry("quarkus.langchain4j.openai.parasol-chat.base-url", "${parasol.models.base-url}"),
				Map.entry("quarkus.langchain4j.openai.parasol-chat.api-key", "${quarkus.langchain4j.openai.api-key}"),
				Map.entry("quarkus.langchain4j.easy-rag.ingestion-strategy", "ON"),
				// the judge on the local Ollama, through its OpenAI-compatible endpoint
				Map.entry("quarkus.langchain4j.openai.judge.base-url", "http://localhost:11434/v1"),
				Map.entry("quarkus.langchain4j.openai.judge.api-key", "ollama"),
				Map.entry("quarkus.langchain4j.openai.judge.chat-model.model-name", "granite4:micro"),
				// the live dev-mode Langfuse, not a fresh Dev Service
				Map.entry("quarkus.langfuse.devservices.enabled", "false"),
				Map.entry("quarkus.langfuse.base-url", "${LF_URL}"),
				Map.entry("quarkus.langfuse.public-key", "quarkus"),
				Map.entry("quarkus.langfuse.secret-key", "${LF_SECRET}"),
				// replays must not record themselves back into the dataset they read
				Map.entry("aiscoring.langfuse.evaluation.session.create-dataset-on-session-close", "false"),
				Map.entry("spike.dataset-version", "${SPIKE_DATASET_VERSION:}"));
		}
	}
}
