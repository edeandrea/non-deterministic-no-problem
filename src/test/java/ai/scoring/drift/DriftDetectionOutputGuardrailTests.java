package ai.scoring.drift;

import static dev.langchain4j.test.guardrail.GuardrailAssertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.InjectMock;
import io.quarkus.test.Mock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import ai.scoring.config.InteractionMode;
import ai.scoring.config.ScoringConfig;
import ai.scoring.langfuse.evaluation.Evaluator;
import ai.scoring.drift.DriftDetectionOutputGuardrailTests.KeysTestProfile;
import com.langfuse.api.LangfuseApi;
import com.langfuse.api.datasetItems.DatasetItemsApi.APIDatasetItemsCreateRequest;
import com.langfuse.api.datasetItems.DatasetItemsApi.APIDatasetItemsDeleteRequest;
import com.langfuse.api.datasetItems.DatasetItemsApi.APIDatasetItemsListRequest;
import com.langfuse.api.datasets.DatasetsApi.APIDatasetsCreateRequest;
import com.langfuse.api.datasets.DatasetsApi.APIDatasetsGetRequest;
import com.langfuse.api.model.CreateDatasetItemRequest;
import com.langfuse.api.model.CreateDatasetRequest;
import com.langfuse.api.model.DatasetStatus;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.GuardrailRequestParams;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import io.quarkiverse.langchain4j.guardrails.NoopChatExecutor;
import io.quarkiverse.langchain4j.runtime.aiservice.NoopChatMemory;
import io.quarkiverse.langchain4j.testing.evaluation.EvaluationResult;
import io.quarkiverse.langchain4j.testing.evaluation.EvaluationSample;
import io.quarkiverse.langfuse.client.LangfuseNotFoundException;
import io.smallrye.config.Config;
import io.smallrye.config.SmallRyeConfig;

@QuarkusTest
@TestProfile(KeysTestProfile.class)
class DriftDetectionOutputGuardrailTests {
	private static final String DATASET_NAME = "SomeInterface.method";
	private static final OutputGuardrailRequest DEFAULT_REQUEST = OutputGuardrailRequest.builder()
			.chatExecutor(new NoopChatExecutor())
			.requestParams(GuardrailRequestParams.builder()
				.chatMemory(new NoopChatMemory())
				.invocationContext(InvocationContext.builder()
					.interfaceName("SomeInterface")
					.invocationId(UUID.randomUUID())
					.methodName("method")
					.userMessage(UserMessage.from("User Message"))
					.build())
				.userMessageTemplate("User Message")
				.variables(Map.of())
				.build())
			.responseFromLLM(ChatResponse.builder()
				.finishReason(FinishReason.STOP)
				.modelName("gpt-5-mini")
				.aiMessage(AiMessage.from("Some response"))
				.build())
			.build();

	@InjectMock
	ScoringConfig scoringConfig;

	@Inject
	DriftDetectionOutputGuardrail rescoringOutputGuardrail;

	@InjectMock
	Evaluator evaluator;

	@Inject
	LangfuseApi langfuseApi;

	@BeforeEach
	void setup() {
		// Create the dataset & items if it doesn't already exist
		try {
			this.langfuseApi.datasets()
			                .datasetsGet(APIDatasetsGetRequest.newBuilder()
			                                                  .datasetName(DATASET_NAME)
			                                                  .build());
		}
		catch (LangfuseNotFoundException ex) {
			// Dataset not there, so create it
			this.langfuseApi.datasets()
				.datasetsCreate(APIDatasetsCreateRequest.newBuilder()
					.createDatasetRequest(CreateDatasetRequest.builder()
						.name(DATASET_NAME)
						.build()
					)
					.build());
		}

		// Now create the dataset items
		IntStream.range(0, 2)
		         .forEach(i ->
			         this.langfuseApi.datasetItems()
			                         .datasetItemsCreate(APIDatasetItemsCreateRequest.newBuilder().createDatasetItemRequest(
																 CreateDatasetItemRequest.builder()
																                         .datasetName(DATASET_NAME)
																                         .status(DatasetStatus.ACTIVE)
																                         .input("%s-%d".formatted(DATASET_NAME, i))
																                         .build())
			                                                                         .build()
					));
	}

	@AfterEach
	void tearDown() {
		this.langfuseApi.datasetItems()
			.datasetItemsList(APIDatasetItemsListRequest.newBuilder()
				.datasetName(DATASET_NAME)
				.build())
			.getData()
			.stream()
			.map(dataset -> APIDatasetItemsDeleteRequest.newBuilder().id(dataset.getId()).build())
			.forEach(this.langfuseApi.datasetItems()::datasetItemsDelete);
	}

	@Test
	void normalInteractionMode() {
		when(this.scoringConfig.interactionMode())
			.thenReturn(InteractionMode.NORMAL);

		assertThat(this.rescoringOutputGuardrail.validate(DEFAULT_REQUEST))
			.isSuccessful();

		verifyNoInteractions(this.evaluator);
	}

	@Test
	void driftDetectionPasses() {
		when(this.scoringConfig.interactionMode())
			.thenReturn(InteractionMode.DRIFT_DETECTION);

		when(this.scoringConfig.threshold())
			.thenReturn(0.7);

		when(this.evaluator.evaluate(any(EvaluationSample.class), eq("Some response")))
			.thenReturn(EvaluationResult.passed(1.0));

		assertThat(this.rescoringOutputGuardrail.validate(DEFAULT_REQUEST))
			.isSuccessful();

		verify(this.evaluator, times(2)).evaluate(any(EvaluationSample.class), eq("Some response"));
	}

	@Test
	void driftDetectionFails() {
		when(this.scoringConfig.interactionMode())
			.thenReturn(InteractionMode.DRIFT_DETECTION);

		when(this.scoringConfig.threshold())
			.thenReturn(0.7);

		when(this.evaluator.evaluate(any(EvaluationSample.class), eq("Some response")))
			.thenReturn(EvaluationResult.failed(0.5, "Evaluation wasn't good"));

		assertThat(this.rescoringOutputGuardrail.validate(DEFAULT_REQUEST))
			.hasSingleFailureWithMessage("Score is below threshold of 0.7");

		verify(this.evaluator, times(2)).evaluate(any(EvaluationSample.class), eq("Some response"));
	}

	public static class ScoringConfigMockProducer {
		@Inject
		Config config;

		@Produces
		@ApplicationScoped
		@Mock
		ScoringConfig scoringConfig() {
			return this.config
				.unwrap(SmallRyeConfig.class)
				.getConfigMapping(ScoringConfig.class);
		}
	}

	public static class KeysTestProfile implements QuarkusTestProfile {
		@Override
		public Map<String, String> getConfigOverrides() {
			return Map.of(
				"quarkus.langchain4j.openai.api-key", "changeme",
				"quarkus.langchain4j.openai.session-sentiment.api-key", "changeme",
				"quarkus.langchain4j.openai.judge.api-key", "changeme"
			);
		}
	}
}