package ai.scoring.drift;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.parasol.model.claim.ClaimBotQuery;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import ai.scoring.drift.DriftDetectionChatRouteExceptionHandlerTests.DriftHandlerTestProfile;
import com.github.tomakehurst.wiremock.client.WireMock;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import io.quarkiverse.langchain4j.chatscopes.LocalChatRoutes;
import io.quarkiverse.wiremock.devservice.ConnectWireMock;

/**
 * End-to-end: a drift failure raised by the guardrail on {@code ClaimService.chat} must reach the websocket client
 * on its <em>error</em> channel (so the UI can treat it as a failure of the request), with the drift details in the
 * error text, and must <em>not</em> be delivered as a regular chat message as if it were a normal answer.
 * <p>
 * The guardrail itself is mocked to return a fatal drift result so this test doesn't need a Langfuse dataset or an
 * evaluation strategy - those are covered by {@link DriftDetectionOutputGuardrailTests}.
 */
@QuarkusTest
@TestProfile(DriftHandlerTestProfile.class)
@ConnectWireMock
class DriftDetectionChatRouteExceptionHandlerTests {
	private static final DriftDetectionException DRIFT = DriftDetectionException.builder()
		.sampleSetName("langchain4j.aiservices.ClaimService.chat")
		.score(0.42)
		.threshold(0.7)
		.build();

	private static final String OPENAI_RESPONSE = """
		{
			"id": "chatcmpl-test",
			"object": "chat.completion",
			"created": 1234567890,
			"model": "some-model",
			"choices": [
				{
					"index": 0,
					"message": {
						"role": "assistant",
						"content": "Based on the policy, your claim is currently under review."
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

	@InjectMock
	DriftDetectionOutputGuardrail driftGuardrail;

	@Inject
	LocalChatRoutes chatRoutes;

	WireMock wiremock;

	@BeforeEach
	void setup() {
		this.wiremock.register(
			post(urlPathEqualTo("/v1/chat/completions"))
				.willReturn(okJson(OPENAI_RESPONSE))
		);

		// Easy RAG embeds the user query before the chat call; stub a zero vector so it has something to work with
		var embeddingVector = IntStream.range(0, 1536)
		                               .mapToObj(i -> "0.0")
		                               .collect(Collectors.joining(",", "[", "]"));

		this.wiremock.register(
			post(urlPathEqualTo("/v1/embeddings"))
				.willReturn(okJson("""
					{
						"object": "list",
						"data": [{ "object": "embedding", "index": 0, "embedding": %s }],
						"model": "text-embedding-3-small",
						"usage": { "prompt_tokens": 5, "total_tokens": 5 }
					}
					""".formatted(embeddingVector)))
		);

		// Make the guardrail report drift, exactly as DriftDetectionOutputGuardrail.validate does when the score is
		// below threshold: a fatal result whose Failure carries a DriftDetectionException as its cause. Built via the
		// same default fatal(...) helper the real guardrail uses, since Failure's constructors are package-private.
		var results = new OutputGuardrail() {};
		when(this.driftGuardrail.validate(any(OutputGuardrailRequest.class)))
			.thenReturn(results.fatal(DRIFT.getMessage(), DRIFT));
	}

	@Test
	void driftIsDeliveredToTheClientAsAnError() {
		var messages = new CopyOnWriteArrayList<String>();
		var errors = new CopyOnWriteArrayList<String>();

		try (var client = this.chatRoutes.newClient()) {
			var session = client.builder()
			                    .messageHandler(messages::add)
			                    .errorHandler(errors::add)
			                    .connect("chat");

			session.chat(Map.of("query", new ClaimBotQuery(1, "Test claim details", "What is the claim status?", LocalDate.of(2026, 1, 1))));
		}

		// The handler must have surfaced the guardrail failure on the error channel, carrying the drift details ...
		assertThat(errors)
			.singleElement()
			.isEqualTo("DRIFT DETECTED!!!\n\n%s".formatted(DRIFT.getMessage()));

		// ... and NOT as a normal chat message, which the UI would render as if it were the assistant's answer
		assertThat(messages)
			.isEmpty();

		verify(this.driftGuardrail).validate(any(OutputGuardrailRequest.class));
	}

	public static class DriftHandlerTestProfile implements QuarkusTestProfile {
		private static final String WIREMOCK_URL = "http://localhost:${quarkus.wiremock.devservices.port}/v1";

		@Override
		public Map<String, String> getConfigOverrides() {
			return Map.ofEntries(
				// Point every model the chat path touches at WireMock
				Map.entry("quarkus.langchain4j.openai.api-key", "changeme"),
				Map.entry("quarkus.langchain4j.openai.base-url", WIREMOCK_URL),
				Map.entry("quarkus.langchain4j.openai.parasol-chat.api-key", "changeme"),
				Map.entry("quarkus.langchain4j.openai.parasol-chat.base-url", WIREMOCK_URL),
				Map.entry("quarkus.langchain4j.openai.session-sentiment.api-key", "changeme"),
				Map.entry("quarkus.langchain4j.openai.judge.api-key", "changeme"),
				// Keep the rest of the scoring machinery out of the way; we're only interested in the chat route
				Map.entry("quarkus.aiscoring.langfuse.evaluation.initialize-on-startup", "false"),
				Map.entry("quarkus.aiscoring.langfuse.evaluation.session.score-session", "false"),
				Map.entry("quarkus.aiscoring.langfuse.evaluation.session.create-dataset-on-session-close", "false")
			);
		}
	}
}
