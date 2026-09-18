package ai.scoring.langfuse.init;

import static ai.scoring.langfuse.session.SessionSentiment.Sentiment;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;

import ai.scoring.langfuse.config.LangfuseConfig;
import ai.scoring.langfuse.config.LangfuseConfig.Evaluation;
import ai.scoring.langfuse.session.SessionSentiment;
import com.langfuse.api.LangfuseApiException;
import com.langfuse.api.evaluators.EvaluatorsApi.APIEvaluatorsUpdateRequest;
import com.langfuse.api.model.ConfigCategory;
import com.langfuse.api.model.CreateEvaluationRuleRequest;
import com.langfuse.api.model.CreateLlmAsJudgeEvaluatorRequest1;
import com.langfuse.api.model.CreateModelRequest;
import com.langfuse.api.model.CreateScoreConfigRequest;
import com.langfuse.api.model.EvaluationRule;
import com.langfuse.api.model.EvaluationRuleEvaluatorAssignmentInput;
import com.langfuse.api.model.EvaluationRuleFilter;
import com.langfuse.api.model.EvaluationRuleOptionsFilterOperator;
import com.langfuse.api.model.Evaluator;
import com.langfuse.api.model.EvaluatorChatPromptInput;
import com.langfuse.api.model.EvaluatorModelConfig;
import com.langfuse.api.model.EvaluatorOutputDefinition;
import com.langfuse.api.model.LlmAdapter;
import com.langfuse.api.model.LlmAsJudgeEvaluator1;
import com.langfuse.api.model.LlmConnection;
import com.langfuse.api.model.Model;
import com.langfuse.api.model.ModelUsageUnit;
import com.langfuse.api.model.PricingTierInput;
import com.langfuse.api.model.PromptVariableMappingInput;
import com.langfuse.api.model.PromptVariableMappingSource;
import com.langfuse.api.model.PublicEvaluatorNumericScore1;
import com.langfuse.api.model.ScoreConfig;
import com.langfuse.api.model.ScoreConfigDataType;
import com.langfuse.api.model.StringOptionsEvaluationRuleFilter1;
import com.langfuse.api.model.UpdateEvaluatorRequest;
import com.langfuse.api.model.UpdateLlmAsJudgeEvaluatorRequest;
import com.langfuse.api.model.UpsertLlmConnectionRequest;
import io.quarkiverse.langfuse.api.LangfuseOperations;

@Singleton
public class LangfuseEvaluationInitializer {
	private static final String EVALUATOR_NAME = "Continuous Evaluation Evaluator";

	private static final String PROMPT = """
		You are an AI evaluating a response and the expected output.
		You need to evaluate whether the response is relevant to the question.

		---
		Input: {{query}}

		---
		Output: {{generation}}
		""";

	private final Evaluation scoringConfig;
	private final LangfuseOperations langfuse;

	public LangfuseEvaluationInitializer(LangfuseConfig langfuseConfig, LangfuseOperations langfuse) {
		this.langfuse = langfuse;
		this.scoringConfig = langfuseConfig.evaluation();
	}

	void onStartup(@Observes StartupEvent event) {
		if (this.scoringConfig.initializeOnStartup()) {
			getOrCreateSessionSentimentScoreConfig()
				.ifPresentOrElse(
					sessionScore -> Log.info("Session Evaluation config set up"),
					() -> Log.warn("Session scoring config setup failed")
				);

			getOrRegisterCohereModelDefinition()
				.ifPresentOrElse(
					model -> Log.info("Cohere model definition set up"),
					() -> Log.warn("Cohere model definition setup failed")
				);

			getOrCreateGeminiLlmConnection()
				.flatMap(this::handleEvaluator)
				.flatMap(this::getOrCreateEvaluationRule)
				.ifPresentOrElse(
					rule -> Log.info("LLM Evaluation set up"),
					() -> Log.warn("LLM Evaluation setup failed")
				);
		}
	}

	private Optional<EvaluationRule> getOrCreateEvaluationRule(Evaluator evaluator) {
		return asLlmAsJudge(evaluator)
			.flatMap(this::createEvaluationRule);
	}

	private Optional<EvaluationRule> createEvaluationRule(LlmAsJudgeEvaluator1 llmEvaluator) {
		Log.infof("Ensuring evaluation rule exists for evaluator %s", llmEvaluator.getName());

		var request = CreateEvaluationRuleRequest.builder()
		                                         .name(EVALUATOR_NAME)
		                                         .enabled(true)
		                                         .sampling(1.0)
		                                         .filter(
			                                         List.of(
				                                         new EvaluationRuleFilter(
					                                         StringOptionsEvaluationRuleFilter1.builder()
					                                                                           .column("environment")
					                                                                           .operator(EvaluationRuleOptionsFilterOperator.NONE_OF)
					                                                                           .type(StringOptionsEvaluationRuleFilter1.TypeEnum.STRING_OPTIONS)
					                                                                           .value(List.of("langfuse-llm-as-a-judge", CreateLlmAsJudgeEvaluatorRequest1.TypeEnum.LLM_AS_JUDGE.getValue()))
					                                                                           .build()
				                                         ),
				                                         new EvaluationRuleFilter(
					                                         StringOptionsEvaluationRuleFilter1.builder()
					                                                                           .column("type")
					                                                                           .operator(EvaluationRuleOptionsFilterOperator.NONE_OF)
					                                                                           .type(StringOptionsEvaluationRuleFilter1.TypeEnum.STRING_OPTIONS)
					                                                                           .value(List.of("SPAN", "EVENT"))
					                                                                           .build()
				                                         )
			                                         )
		                                         )
		                                         .evaluatorAssignments(
			                                         List.of(
				                                         EvaluationRuleEvaluatorAssignmentInput.builder()
				                                                                               .evaluatorId(llmEvaluator.getId())
				                                                                               .variableMapping(variableMapping())
				                                                                               .build()
			                                         )
		                                         )
		                                         .build();

		try {
			var rule = this.langfuse.evaluationRules().createIfAbsent(request);
			Log.infof("Evaluation rule ready: %s", rule.getId());
			return Optional.of(rule);
		}
		catch (LangfuseApiException e) {
			// getMessage() carries the whole raw response body; getServerMessage() is the server's own sentence.
			Log.warnf(e, "Failed to create evaluation rule (HTTP %d): %s", e.getStatusCode(), e.getServerMessage());
			return Optional.empty();
		}
		catch (Exception e) {
			Log.warnf(e, "Failed to create evaluation rule: %s", e.getMessage());
			return Optional.empty();
		}
	}

	private Optional<Evaluator> handleEvaluator(LlmConnection llmConnection) {
		Log.info("Checking to see if relevance evaluator is already registered");

		getOrCreateContinuousScoringScoreConfig()
			.ifPresentOrElse(
				config -> Log.info("Continuous Evaluation score config setup complete"),
				() -> Log.warn("Continuous Evaluation score config setup failed")
			);

		return findExistingEvaluator()
			.map(existing -> ensureEvaluatorModel(existing, llmConnection))
			.or(() -> createEvaluator(llmConnection));
	}

	/**
	 * Looks the evaluator up by name. Note that {@code findByName} is case-SENSITIVE, which is fine here because
	 * {@link #EVALUATOR_NAME} is a constant used for both the lookup and the creation.
	 * <p>
	 * It also resolves evaluators of other types (a code evaluator, say), so the result is filtered back down to
	 * LLM-as-a-judge: a same-named evaluator of another type must not suppress creation of the real one.
	 */
	private Optional<Evaluator> findExistingEvaluator() {
		try {
			return this.langfuse.evaluators()
			                    .findByName(EVALUATOR_NAME)
			                    .filter(existing -> asLlmAsJudge(existing).isPresent());
		}
		catch (LangfuseApiException e) {
			// getMessage() carries the whole raw response body; getServerMessage() is the server's own sentence.
			Log.warnf(e, "Failed to look up evaluator '%s' (HTTP %d): %s", EVALUATOR_NAME, e.getStatusCode(), e.getServerMessage());
			return Optional.empty();
		}
		catch (Exception e) {
			Log.warnf(e, "Failed to look up evaluator '%s': %s", EVALUATOR_NAME, e.getMessage());
			return Optional.empty();
		}
	}

	private Evaluator ensureEvaluatorModel(Evaluator evaluator, LlmConnection llmConnection) {
		var provider = llmConnection.getProvider();
		var model = llmConnection.getCustomModels().getFirst();

		return asLlmAsJudge(evaluator)
			.filter(llmEvaluator -> !modelConfigMatches(llmEvaluator.getModelConfig(), provider, model))
			.flatMap(llmEvaluator -> updateEvaluatorModel(llmEvaluator, provider, model))
			.orElse(evaluator);
	}

	private static boolean modelConfigMatches(EvaluatorModelConfig modelConfig, String provider, String model) {
		return Optional.ofNullable(modelConfig)
		               .filter(config -> provider.equalsIgnoreCase(config.getProvider()))
		               .filter(config -> model.equalsIgnoreCase(config.getModel()))
		               .isPresent();
	}

	private Optional<Evaluator> updateEvaluatorModel(LlmAsJudgeEvaluator1 llmEvaluator, String provider, String model) {
		Log.infof("Re-pointing evaluator %s to model %s/%s", llmEvaluator.getId(), provider, model);

		var request = new UpdateEvaluatorRequest(
			UpdateLlmAsJudgeEvaluatorRequest.builder()
			                                .type(CreateLlmAsJudgeEvaluatorRequest1.TypeEnum.LLM_AS_JUDGE.getValue())
			                                .modelConfig(EvaluatorModelConfig.builder()
			                                                                 .provider(provider)
			                                                                 .model(model)
			                                                                 .build())
			                                .build()
		);

		try {
			var updated = this.langfuse.api()
			                           .evaluators()
			                           .evaluatorsUpdate(APIEvaluatorsUpdateRequest.newBuilder()
			                                                                       .evaluatorId(llmEvaluator.getId())
			                                                                       .updateEvaluatorRequest(request)
			                                                                       .build());
			Log.infof("Updated evaluator model config: %s", llmEvaluator.getId());
			return Optional.of(updated);
		}
		catch (LangfuseApiException e) {
			// getMessage() carries the whole raw response body; getServerMessage() is the server's own sentence.
			Log.warnf(e, "Failed to update evaluator model config (HTTP %d): %s", e.getStatusCode(), e.getServerMessage());
			return Optional.empty();
		}
		catch (Exception e) {
			Log.warnf(e, "Failed to update evaluator model config: %s", e.getMessage());
			return Optional.empty();
		}
	}

	private Optional<Evaluator> createEvaluator(LlmConnection llmConnection) {
		Log.infof("Initializing Continuous Evaluation LLM Evaluator");

		var request = CreateLlmAsJudgeEvaluatorRequest1.builder()
		                                              .type(CreateLlmAsJudgeEvaluatorRequest1.TypeEnum.LLM_AS_JUDGE)
		                                              .name(EVALUATOR_NAME)
		                                              .prompt(new EvaluatorChatPromptInput(PROMPT))
		                                              .modelConfig(EvaluatorModelConfig.builder()
		                                                                               .provider(llmConnection.getProvider())
		                                                                               .model(llmConnection.getCustomModels().getFirst())
		                                                                               .build())
		                                              .variableMapping(variableMapping())
		                                              .outputDefinition(new EvaluatorOutputDefinition(
			                                              PublicEvaluatorNumericScore1.builder()
			                                                                          .dataType(PublicEvaluatorNumericScore1.DataTypeEnum.NUMERIC)
			                                                                          .scoreReasoningInstructions("Explain the assigned score in one concise sentence.")
			                                                                          .scoreValueInstructions("Return a numeric score between 0 and 1, where 0 means \"completely irrelevant\" and 1 means \"completely relevant\".")
			                                                                          .minValue(0.0)
			                                                                          .maxValue(1.0)
			                                                                          .build()
		                                              ))
		                                              .build();

		try {
			var evaluator = this.langfuse.evaluators().createIfAbsent(request);
			asLlmAsJudge(evaluator)
				.ifPresent(llmEvaluator -> Log.infof("Registered Continuous Evaluation LLM Evaluator: %s", llmEvaluator.getId()));
			return Optional.of(evaluator);
		}
		catch (LangfuseApiException e) {
			// getMessage() carries the whole raw response body; getServerMessage() is the server's own sentence.
			Log.warnf(e, "Failed to initialize Continuous Evaluation LLM Evaluator (HTTP %d): %s", e.getStatusCode(), e.getServerMessage());
			return Optional.empty();
		}
		catch (Exception e) {
			Log.warnf(e, "Failed to initialize Continuous Evaluation LLM Evaluator: %s", e.getMessage());
			return Optional.empty();
		}
	}

	private static Optional<LlmAsJudgeEvaluator1> asLlmAsJudge(Evaluator evaluator) {
		return Optional.ofNullable(evaluator.getActualInstance())
		               .filter(LlmAsJudgeEvaluator1.class::isInstance)
		               .map(LlmAsJudgeEvaluator1.class::cast);
	}

	private static List<PromptVariableMappingInput> variableMapping() {
		return List.of(
			PromptVariableMappingInput.builder()
			                          .variable("query")
			                          .source(PromptVariableMappingSource.INPUT)
			                          .build(),
			PromptVariableMappingInput.builder()
			                          .variable("generation")
			                          .source(PromptVariableMappingSource.OUTPUT)
			                          .build()
		);
	}

	/**
	 * {@code findByProvider} only answers "absent" for a genuine not-found; a 401/403/5xx or a transport failure
	 * propagates. As this runs from {@code onStartup}, those have to be caught here or they abort application boot.
	 * <p>
	 * The {@link IllegalStateException} raised when the Gemini API key is missing is deliberate and must still
	 * propagate, hence the explicit rethrow.
	 */
	private Optional<LlmConnection> getOrCreateGeminiLlmConnection() {
		try {
			return this.langfuse.llmConnections()
			                    .findByProvider(LlmAdapter.GOOGLE_AI_STUDIO.getValue())
			                    .or(this::createGeminiLlmConnection);
		}
		catch (IllegalStateException e) {
			throw e;
		}
		catch (LangfuseApiException e) {
			// getMessage() carries the whole raw response body; getServerMessage() is the server's own sentence.
			Log.warnf(e, "Failed to set up %s LLM Connection (HTTP %d): %s", LlmAdapter.GOOGLE_AI_STUDIO.getValue(), e.getStatusCode(), e.getServerMessage());
			return Optional.empty();
		}
		catch (Exception e) {
			Log.warnf(e, "Failed to set up %s LLM Connection: %s", LlmAdapter.GOOGLE_AI_STUDIO.getValue(), e.getMessage());
			return Optional.empty();
		}
	}

	private Optional<LlmConnection> createGeminiLlmConnection() {
		var gemini = this.scoringConfig.gemini();
		var apiKey = gemini.apiKey()
		                   .orElseThrow(() -> new IllegalStateException("Gemini API Key must be set to initialize the Gemini LLM Connection"));

		Log.infof("Initializing Gemini LLM Connection to model %s", gemini.modelName());

		var request = UpsertLlmConnectionRequest.builder()
		                                        .provider(LlmAdapter.GOOGLE_AI_STUDIO.getValue())
		                                        .adapter(LlmAdapter.GOOGLE_AI_STUDIO)
		                                        .secretKey(apiKey)
		                                        .customModels(List.of(gemini.modelName()))
		                                        .build();

		try {
			var connection = this.langfuse.llmConnections().upsert(request);
			Log.infof("Registered %s LLM Connection: %s", request.getProvider(), connection.getId());
			return Optional.of(connection);
		}
		catch (LangfuseApiException e) {
			// getMessage() carries the whole raw response body; getServerMessage() is the server's own sentence.
			Log.warnf(e, "Failed to initialize %s LLM Connection (HTTP %d): %s", request.getProvider(), e.getStatusCode(), e.getServerMessage());
			return Optional.empty();
		}
		catch (Exception e) {
			Log.warnf(e, "Failed to initialize %s LLM Connection: %s", request.getProvider(), e.getMessage());
			return Optional.empty();
		}
	}

	private Optional<ScoreConfig> getOrCreateContinuousScoringScoreConfig() {
		Log.info("Ensuring Continuous Evaluation Evaluator score config exists");

		var request = CreateScoreConfigRequest.builder()
		                                      .name(EVALUATOR_NAME)
		                                      .dataType(ScoreConfigDataType.NUMERIC)
		                                      .minValue(0.0)
		                                      .maxValue(1.0)
		                                      .description("Relevance score for individual AI responses. 0 = completely irrelevant, 1 = completely relevant.")
		                                      .build();

		try {
			var config = this.langfuse.scoreConfigs().createIfAbsent(request);
			Log.infof("Continuous Evaluation score config ready (id=%s)", config.getId());
			return Optional.of(config);
		}
		catch (LangfuseApiException e) {
			// getMessage() carries the whole raw response body; getServerMessage() is the server's own sentence.
			Log.warnf(e, "Failed to create Continuous Evaluation score config (HTTP %d): %s", e.getStatusCode(), e.getServerMessage());
			return Optional.empty();
		}
		catch (Exception e) {
			Log.warnf(e, "Failed to create Continuous Evaluation score config: %s", e.getMessage());
			return Optional.empty();
		}
	}

	private Optional<ScoreConfig> getOrCreateSessionSentimentScoreConfig() {
		Log.info("Ensuring session-sentiment score config exists");

		var categories = Arrays.stream(Sentiment.values())
		                       .map(s -> ConfigCategory.builder()
		                                               .label(s.label())
		                                               .value(s.value())
		                                               .build())
		                       .toList();

		var request = CreateScoreConfigRequest.builder()
		                                      .name(SessionSentiment.SCORE_NAME)
		                                      .dataType(ScoreConfigDataType.CATEGORICAL)
		                                      .categories(categories)
		                                      .description("Overall user sentiment for the conversation session. Evaluates whether the user's queries were answered, if they left satisfied, or if they appeared frustrated.")
		                                      .build();

		try {
			var config = this.langfuse.scoreConfigs().createIfAbsent(request);
			Log.infof("Session-sentiment score config ready (id=%s)", config.getId());
			return Optional.of(config);
		}
		catch (LangfuseApiException e) {
			// getMessage() carries the whole raw response body; getServerMessage() is the server's own sentence.
			Log.warnf(e, "Failed to create session-sentiment score config (HTTP %d): %s", e.getStatusCode(), e.getServerMessage());
			return Optional.empty();
		}
		catch (Exception e) {
			Log.warnf(e, "Failed to create session-sentiment score config: %s", e.getMessage());
			return Optional.empty();
		}
	}

	private Optional<Model> getOrRegisterCohereModelDefinition() {
		Log.info("Ensuring Cohere model pricing definition exists");

		var request = CreateModelRequest.builder()
		                                .modelName("command-r7b")
		                                .matchPattern("(?i)^(command-r7b)(-.+)?$")
		                                .unit(ModelUsageUnit.TOKENS)
		                                .pricingTiers(
			                                List.of(
				                                PricingTierInput.builder()
				                                                .isDefault(true)
				                                                .priority(0)
				                                                .name("standard")
				                                                .prices(
					                                                Map.of(
						                                                "input", 0.00000004,
						                                                "output", 0.00000015
					                                                )
				                                                )
				                                                .build()
			                                ))
		                                .build();

		try {
			var model = this.langfuse.models().createIfAbsent(request);
			Log.infof("Cohere model pricing definition ready in Langfuse (id=%s)", model.getId());
			return Optional.of(model);
		}
		catch (LangfuseApiException e) {
			// getMessage() carries the whole raw response body; getServerMessage() is the server's own sentence.
			// A 404 here is not necessarily "missing": Langfuse also answers 404 for some refusals, and only the
			// server message tells the two apart.
			Log.warnf(e, "Could not register model '%s' in Langfuse (HTTP %d): %s", request.getModelName(), e.getStatusCode(), e.getServerMessage());
			return Optional.empty();
		}
		catch (Exception e) {
			Log.warnf(e, "Could not register model '%s' in Langfuse: %s", request.getModelName(), e.getMessage());
			return Optional.empty();
		}
	}
}
