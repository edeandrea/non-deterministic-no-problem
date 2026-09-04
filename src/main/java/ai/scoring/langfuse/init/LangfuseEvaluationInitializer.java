package ai.scoring.langfuse.init;

import static ai.scoring.langfuse.session.SessionSentiment.Sentiment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;

import ai.scoring.langfuse.config.LangfuseConfig;
import ai.scoring.langfuse.config.LangfuseConfig.Evaluation;
import ai.scoring.langfuse.session.SessionSentiment;
import com.langfuse.api.LangfuseApi;
import com.langfuse.api.evaluationRules.EvaluationRulesApi.APIEvaluationRulesCreateRequest;
import com.langfuse.api.evaluators.EvaluatorsApi.APIEvaluatorsCreateRequest;
import com.langfuse.api.evaluators.EvaluatorsApi.APIEvaluatorsListRequest;
import com.langfuse.api.evaluators.EvaluatorsApi.APIEvaluatorsUpdateRequest;
import com.langfuse.api.model.ConfigCategory;
import com.langfuse.api.model.CreateEvaluationRuleRequest;
import com.langfuse.api.model.CreateEvaluatorRequest;
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
import com.langfuse.api.scoreConfigs.ScoreConfigsApi.APIScoreConfigsCreateRequest;

@Singleton
public non-sealed class LangfuseEvaluationInitializer extends LangfuseInitializer {
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

	public LangfuseEvaluationInitializer(LangfuseConfig langfuseConfig, LangfuseApi langfuseApi) {
		super(langfuseApi);
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
			.flatMap(llmEvaluator -> getExistingEvaluationRule(EVALUATOR_NAME)
				.or(() -> createEvaluationRule(llmEvaluator)));
	}

	private Optional<EvaluationRule> createEvaluationRule(LlmAsJudgeEvaluator1 llmEvaluator) {
		Log.infof("Creating evaluation rule for evaluator %s", llmEvaluator.getName());

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
			var rule = this.langfuseApi.evaluationRules()
			                           .evaluationRulesCreate(APIEvaluationRulesCreateRequest.newBuilder()
			                                                                                 .createEvaluationRuleRequest(request)
			                                                                                 .build());
			Log.infof("Created evaluation rule: %s", rule.getId());
			return Optional.of(rule);
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

		return listEvaluators()
			.filter(evaluator -> asLlmAsJudge(evaluator)
				.map(LlmAsJudgeEvaluator1::getName)
				.filter(EVALUATOR_NAME::equalsIgnoreCase)
				.isPresent())
			.findFirst()
			.map(existing -> ensureEvaluatorModel(existing, llmConnection))
			.or(() -> createEvaluator(llmConnection));
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
			var updated = this.langfuseApi.evaluators()
			                              .evaluatorsUpdate(APIEvaluatorsUpdateRequest.newBuilder()
			                                                                          .evaluatorId(llmEvaluator.getId())
			                                                                          .updateEvaluatorRequest(request)
			                                                                          .build());
			Log.infof("Updated evaluator model config: %s", llmEvaluator.getId());
			return Optional.of(updated);
		}
		catch (Exception e) {
			Log.warnf(e, "Failed to update evaluator model config: %s", e.getMessage());
			return Optional.empty();
		}
	}

	private Stream<Evaluator> listEvaluators() {
		var evaluators = new ArrayList<Evaluator>();
		var cursor = (String) null;

		do {
			var page = this.langfuseApi.evaluators()
			                           .evaluatorsList(APIEvaluatorsListRequest.newBuilder()
			                                                                   .limit(100)
			                                                                   .cursor(cursor)
			                                                                   .build());
			evaluators.addAll(page.getData());
			cursor = page.getMeta().getCursor();
		}
		while (cursor != null);

		return evaluators.stream();
	}

	private Optional<Evaluator> createEvaluator(LlmConnection llmConnection) {
		Log.infof("Initializing Continuous Evaluation LLM Evaluator");

		var request = new CreateEvaluatorRequest(
			CreateLlmAsJudgeEvaluatorRequest1.builder()
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
			                                 .build()
		);

		try {
			var evaluator = this.langfuseApi.evaluators()
			                                .evaluatorsCreate(APIEvaluatorsCreateRequest.newBuilder()
			                                                                            .createEvaluatorRequest(request)
			                                                                            .build());
			asLlmAsJudge(evaluator)
				.ifPresent(llmEvaluator -> Log.infof("Registered Continuous Evaluation LLM Evaluator: %s", llmEvaluator.getId()));
			return Optional.of(evaluator);
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

	private Optional<LlmConnection> getOrCreateGeminiLlmConnection() {
		return getExistingLlmConnection(LlmAdapter.GOOGLE_AI_STUDIO.getValue())
			.or(() -> {
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

				return createLLMConnection(request);
			});
	}

	private Optional<ScoreConfig> getOrCreateContinuousScoringScoreConfig() {
		return getExistingScoreConfig(EVALUATOR_NAME)
			.or(() -> {
				Log.info("Creating Continuous Evaluation Evaluator score config");

				var request = CreateScoreConfigRequest.builder()
				                                      .name(EVALUATOR_NAME)
				                                      .dataType(ScoreConfigDataType.NUMERIC)
				                                      .minValue(0.0)
				                                      .maxValue(1.0)
				                                      .description("Relevance score for individual AI responses. 0 = completely irrelevant, 1 = completely relevant.")
				                                      .build();

				try {
					var config = this.langfuseApi.scoreConfigs()
					                             .scoreConfigsCreate(APIScoreConfigsCreateRequest.newBuilder()
					                                                                             .createScoreConfigRequest(request)
					                                                                             .build());
					Log.infof("Created Continuous Evaluation score config (id=%s)", config.getId());
					return Optional.of(config);
				}
				catch (Exception e) {
					Log.warnf(e, "Failed to create Continuous Evaluation score config: %s", e.getMessage());
					return Optional.empty();
				}
			});
	}

	private Optional<ScoreConfig> getOrCreateSessionSentimentScoreConfig() {
		return getExistingScoreConfig(SessionSentiment.SCORE_NAME)
			.or(() -> {
				Log.info("Creating session-sentiment score config");

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
					var config = this.langfuseApi.scoreConfigs()
					                             .scoreConfigsCreate(APIScoreConfigsCreateRequest.newBuilder()
					                                                                             .createScoreConfigRequest(request)
					                                                                             .build());
					Log.infof("Created session-sentiment score config (id=%s)", config.getId());
					return Optional.of(config);
				}
				catch (Exception e) {
					Log.warnf(e, "Failed to create session-sentiment score config: %s", e.getMessage());
					return Optional.empty();
				}
			});
	}

	private Optional<Model> getOrRegisterCohereModelDefinition() {
		return getExistingModelDefinition("command-r7b")
			.or(() -> {
				Log.info("Registering Cohere model");
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

				return createModel(request);
			});
	}
}
