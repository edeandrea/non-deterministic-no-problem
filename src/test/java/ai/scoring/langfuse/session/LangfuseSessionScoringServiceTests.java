package ai.scoring.langfuse.session;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.parasol.model.claim.ClaimBotQuery;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import ai.scoring.langfuse.config.LangfuseConfig;
import ai.scoring.langfuse.session.LangfuseSessionScoringServiceTests.SessionScoringTestProfile;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.langfuse.api.LangfuseApi;
import com.langfuse.api.datasetItems.DatasetItemsApi.APIDatasetItemsListRequest;
import com.langfuse.api.datasets.DatasetsApi.APIDatasetsListRequest;
import com.langfuse.api.model.ScoreSubjectSessionV31;
import com.langfuse.api.observations.ObservationsApi.APIObservationsGetManyRequest;
import com.langfuse.api.scoresV3.ScoresV3Api.APIScoresV3GetManyV3Request;
import io.quarkiverse.langchain4j.chatscopes.ChatScopeEnded;
import io.quarkiverse.langchain4j.chatscopes.LocalChatRoutes;
import io.quarkiverse.wiremock.devservice.ConnectWireMock;

@QuarkusTest
@TestProfile(SessionScoringTestProfile.class)
@ConnectWireMock
class LangfuseSessionScoringServiceTests {
	private static final String OPENAI_RESPONSE = """
		{
			"id": "chatcmpl-test",
			"object": "chat.completion",
			"created": 1234567890,
			"model": "gpt-5-mini",
			"choices": [
				{
					"index": 0,
					"message": {
						"role": "assistant",
						"content": "Based on the policy, your claim CLM-001 is currently under review."
					},
					"finish_reason": "stop"
				}
			],
			"usage": {
				"prompt_tokens": 100,
				"completion_tokens": 50,
				"total_tokens": 150
			}
		}
		""";

	private static final String SENTIMENT_RESPONSE = """
		{
			"id": "chatcmpl-sentiment",
			"object": "chat.completion",
			"created": 1234567890,
			"model": "command-r7b-12-2024",
			"choices": [
				{
					"index": 0,
					"message": {
						"role": "assistant",
						"content": "{\\"sentiment\\": \\"POSITIVE\\", \\"reasoning\\": \\"The user's question about claim status was answered satisfactorily.\\"}"
					},
					"finish_reason": "stop"
				}
			],
			"usage": {
				"prompt_tokens": 200,
				"completion_tokens": 30,
				"total_tokens": 230
			}
		}
		""";

	@Inject
	LangfuseApi langfuseApi;

	@Inject
	LangfuseConfig langfuseConfig;

	@Inject
	LocalChatRoutes chatRoutes;

	@Inject
	SessionIdCapture sessionIdCapture;

	WireMock wiremock;

	@BeforeEach
	void setupWireMock() {
		this.wiremock.register(
			post(urlPathEqualTo("/v1/chat/completions"))
				.inScenario("chat-flow")
				.whenScenarioStateIs(Scenario.STARTED)
				.willReturn(okJson(OPENAI_RESPONSE))
				.willSetStateTo("sentiment-phase")
		);

		this.wiremock.register(
			post(urlPathEqualTo("/v1/chat/completions"))
				.inScenario("chat-flow")
				.whenScenarioStateIs("sentiment-phase")
				.willReturn(okJson(SENTIMENT_RESPONSE))
		);

		var embeddingVector = IntStream.range(0, 1536)
		                               .mapToObj(i -> "0.0")
		                               .collect(Collectors.joining(",", "[", "]"));

		this.wiremock.register(
			post(urlPathEqualTo("/v1/embeddings"))
				.willReturn(okJson("""
						{
							"object": "list",
							"data": [{"object": "embedding", "index": 0, "embedding": %s}],
							"model": "text-embedding-ada-002",
							"usage": {"prompt_tokens": 10, "total_tokens": 10}
						}
						""".formatted(embeddingVector)))
		);
	}

	@Test
	void canFetchObservationsBySessionId() {
		var messages = new ArrayList<String>();

		try (var client = this.chatRoutes.newClient()) {
			var session = client.builder()
				.messageHandler(messages::add)
				.connect("chat");

			session.chat(Map.of("query", new ClaimBotQuery(1, "Test claim details", "What is the claim status?", LocalDate.of(2026, 1, 1))));
		}

		await()
			.atMost(Duration.ofSeconds(30))
			.untilAsserted(() ->
				assertThat(messages)
					.as("Messages should have 1 message")
					.hasSize(1));

		await()
			.atMost(Duration.ofSeconds(30))
			.pollInterval(Duration.ofSeconds(2))
			.pollDelay(Duration.ofSeconds(2))
			.untilAsserted(() ->
				assertThat(this.sessionIdCapture.getSessionIds())
					.as("Session ids should have 1 session")
					.hasSize(1));

		var sessionId = this.sessionIdCapture.getSessionIds().getLast();
		var sessionFilter = """
			[{"type":"string","column":"sessionId","operator":"=","value":"%s"}]""".formatted(sessionId);

		await().atMost(Duration.ofSeconds(15))
	     .pollInterval(Duration.ofSeconds(2))
	     .pollDelay(Duration.ofSeconds(2))
	     .untilAsserted(() -> {
	       var observations = this.langfuseApi.observations()
		       .observationsGetMany(APIObservationsGetManyRequest.newBuilder()
			       .filter(sessionFilter)
			       .fields("core,basic,io")
			       .build())
		       .getData();

	       assertThat(observations)
		       .isNotEmpty()
		       .allSatisfy(obs -> assertThat(obs.getSessionId()).isEqualTo(sessionId));

	       var observationsWithIo = observations.stream()
		       .filter(obs -> (obs.getInput() != null) && (obs.getOutput() != null))
		       .toList();

	       assertThat(observationsWithIo)
		       .singleElement()
		       .satisfies(obs -> {
			       assertThat(obs.getName()).isNotBlank();
			       assertThat(obs.getStartTime()).isNotNull();
		       });

	       var exchange = ConversationExchange.from(observationsWithIo.getFirst());
	       assertThat(exchange.traceName()).isNotBlank();
	       assertThat(exchange.traceId()).isNotBlank();
	     });

		// Wait for async session scoring to complete and verify score
		await().atMost(Duration.ofSeconds(30))
		       .pollInterval(Duration.ofSeconds(2))
		       .pollDelay(this.langfuseConfig.evaluation().session().otelFlushWaitTime())
		       .untilAsserted(() -> {
			       var scores = this.langfuseApi.scoresV3()
				       .scoresV3GetManyV3(APIScoresV3GetManyV3Request.newBuilder()
				                                                     .name(SessionSentiment.SCORE_NAME)
				                                                     .sessionId(sessionId)
				                                                     .fields("details,subject")
				                                                     .build())
				       .getData();

			       assertThat(scores)
				       .singleElement()
				       .satisfies(score -> {
					       var categorical = score.getCategoricalScoreV31();
					       assertThat(categorical.getName()).isEqualTo(SessionSentiment.SCORE_NAME);
					       assertThat(categorical.getValue()).isEqualTo("POSITIVE");
					       assertThat(categorical.getComment()).isNotBlank();

					       var subject = categorical.getSubject().getScoreSubjectSessionV31();
					       assertThat(subject.getKind()).isEqualTo(ScoreSubjectSessionV31.KindEnum.SESSION);
					       assertThat(subject.getId()).isEqualTo(sessionId);
				       });
		       });

		// Verify dataset was created with items matching the conversation exchange
		var datasets = this.langfuseApi.datasets()
			.datasetsList(APIDatasetsListRequest.newBuilder().build())
			.getData();

		assertThat(datasets)
			.singleElement()
			.satisfies(dataset -> {
				assertThat(dataset.getName()).isNotBlank();

				var items = this.langfuseApi.datasetItems()
					.datasetItemsList(APIDatasetItemsListRequest.newBuilder()
						.datasetName(dataset.getName())
						.build())
					.getData();

				assertThat(items)
					.singleElement()
					.satisfies(item -> {
						assertThat(item.getDatasetName()).isEqualTo(dataset.getName());
						assertThat(item.getInput()).isNotNull();
						assertThat(item.getExpectedOutput()).isNotNull();
						assertThat(item.getSourceTraceId()).isNotBlank();
						assertThat(String.valueOf(item.getMetadata())).contains(sessionId);
					});
			});
	}

	@ApplicationScoped
	static class SessionIdCapture {
		private final CopyOnWriteArrayList<String> sessionIds = new CopyOnWriteArrayList<>();

		void onSessionEnded(@Observes ChatScopeEnded event) {
			this.sessionIds.add(event.scope().getId());
		}

		List<String> getSessionIds() {
			return this.sessionIds;
		}
	}

	public static class SessionScoringTestProfile implements QuarkusTestProfile {
		private static final String WIREMOCK_URL = "http://localhost:${quarkus.wiremock.devservices.port}/v1";

		@Override
		public Map<String, String> getConfigOverrides() {
			return Map.ofEntries(
				Map.entry("quarkus.aiscoring.interaction-mode", "normal"),
				Map.entry("quarkus.aiscoring.langfuse.evaluation.initialize-on-startup", "true"),
				Map.entry("quarkus.aiscoring.langfuse.evaluation.session.score-session", "true"),
				Map.entry("quarkus.aiscoring.langfuse.evaluation.session.create-dataset-on-session-close", "true"),
				Map.entry("quarkus.otel.exporter.otlp.enabled", "true"),
				Map.entry("quarkus.langchain4j.openai.api-key", "changeme"),
				Map.entry("quarkus.langchain4j.openai.base-url", WIREMOCK_URL),
				Map.entry("quarkus.langchain4j.openai.parasol-chat.api-key", "changeme"),
				Map.entry("quarkus.langchain4j.openai.parasol-chat.base-url", WIREMOCK_URL),
				Map.entry("quarkus.langchain4j.openai.session-sentiment.api-key", "changeme"),
				Map.entry("quarkus.langchain4j.openai.session-sentiment.base-url", WIREMOCK_URL),
				Map.entry("quarkus.langchain4j.openai.judge.api-key", "changeme"),
				Map.entry("quarkus.langchain4j.openai.judge.base-url", WIREMOCK_URL)
			);
		}
	}
}
