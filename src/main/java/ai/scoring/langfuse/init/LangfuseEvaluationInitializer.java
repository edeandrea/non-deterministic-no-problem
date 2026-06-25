package ai.scoring.langfuse.init;

import static ai.scoring.langfuse.session.SessionSentiment.Sentiment;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;

import ai.scoring.langfuse.config.LangfuseConfig;
import ai.scoring.langfuse.config.LangfuseConfig.Evaluation;
import ai.scoring.langfuse.session.SessionSentiment;
import com.langfuse.api.LangfuseApi;
import com.langfuse.api.model.ConfigCategory;
import com.langfuse.api.model.CreateModelRequest;
import com.langfuse.api.model.CreateScoreConfigRequest;
import com.langfuse.api.model.LlmAdapter;
import com.langfuse.api.model.LlmConnection;
import com.langfuse.api.model.Model;
import com.langfuse.api.model.ModelUsageUnit;
import com.langfuse.api.model.PricingTierInput;
import com.langfuse.api.model.ScoreConfig;
import com.langfuse.api.model.ScoreConfigDataType;
import com.langfuse.api.model.UnstableCreateEvaluationRuleRequest;
import com.langfuse.api.model.UnstableCreateEvaluatorRequest;
import com.langfuse.api.model.UnstableEvaluationRule;
import com.langfuse.api.model.UnstableEvaluationRuleEvaluatorReference;
import com.langfuse.api.model.UnstableEvaluationRuleFilter;
import com.langfuse.api.model.UnstableEvaluationRuleFilterOneOf3;
import com.langfuse.api.model.UnstableEvaluationRuleFilterOneOf3.TypeEnum;
import com.langfuse.api.model.UnstableEvaluationRuleMapping;
import com.langfuse.api.model.UnstableEvaluationRuleMappingSource;
import com.langfuse.api.model.UnstableEvaluationRuleOptionsFilterOperator;
import com.langfuse.api.model.UnstableEvaluationRuleTarget;
import com.langfuse.api.model.UnstableEvaluator;
import com.langfuse.api.model.UnstableEvaluatorModelConfig;
import com.langfuse.api.model.UnstableEvaluatorOutputDefinition;
import com.langfuse.api.model.UnstableEvaluatorOutputDefinitionOneOf;
import com.langfuse.api.model.UnstableEvaluatorOutputDefinitionOneOf.DataTypeEnum;
import com.langfuse.api.model.UnstableEvaluatorOutputFieldDefinition;
import com.langfuse.api.model.UnstableEvaluatorScope;
import com.langfuse.api.model.UnstableEvaluatorType;
import com.langfuse.api.model.UnstableEvaluators;
import com.langfuse.api.model.UpsertLlmConnectionRequest;
import com.langfuse.api.scoreConfigs.ScoreConfigsApi.APIScoreConfigsCreateRequest;
import com.langfuse.api.unstableEvaluationRules.UnstableEvaluationRulesApi.APIUnstableEvaluationRulesCreateRequest;
import com.langfuse.api.unstableEvaluators.UnstableEvaluatorsApi.APIUnstableEvaluatorsCreateRequest;
import com.langfuse.api.unstableEvaluators.UnstableEvaluatorsApi.APIUnstableEvaluatorsListRequest;

@Singleton
public non-sealed class LangfuseEvaluationInitializer extends LangfuseInitializer {
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
				.flatMap(model -> getOrCreateCohereLlmConnection())
				.flatMap(this::handleEvaluator)
				.flatMap(this::getOrCreateEvaluationRule)
				.ifPresentOrElse(
					rule -> Log.info("LLM Evaluation set up"),
					() -> Log.warn("LLM Evaluation setup failed")
				);
		}
	}

	private Optional<UnstableEvaluationRule> getOrCreateEvaluationRule(UnstableEvaluator evaluator) {
		return getExistingEvaluationRule("Continuous Evaluation Evaluator")
			.or(() -> {
				Log.infof("Creating evaluation rule for evaluator %s", evaluator.getName());

				var request = UnstableCreateEvaluationRuleRequest.builder()
				                                                 .name("Continuous Evaluation Evaluator")
				                                                 .evaluator(
					                                                 UnstableEvaluationRuleEvaluatorReference.builder()
					                                                                                         .name(evaluator.getName())
					                                                                                         .scope(isManagedEvaluator(evaluator) ? UnstableEvaluatorScope.MANAGED : UnstableEvaluatorScope.PROJECT)
					                                                                                         .build()
				                                                 )
				                                                 .target(UnstableEvaluationRuleTarget.OBSERVATION)
				                                                 .enabled(true)
				                                                 .sampling(1.0)
				                                                 .filter(
					                                                 List.of(
						                                                 new UnstableEvaluationRuleFilter(
							                                                 UnstableEvaluationRuleFilterOneOf3.builder()
							                                                                                   .column("environment")
							                                                                                   .operator(UnstableEvaluationRuleOptionsFilterOperator.NONE_OF)
							                                                                                   .type(TypeEnum.STRING_OPTIONS)
							                                                                                   .value(List.of(UnstableEvaluatorType.LLM_AS_JUDGE.getValue()))
							                                                                                   .build()
						                                                 ),
						                                                 new UnstableEvaluationRuleFilter(
							                                                 UnstableEvaluationRuleFilterOneOf3.builder()
							                                                                                   .column("type")
							                                                                                   .operator(UnstableEvaluationRuleOptionsFilterOperator.NONE_OF)
							                                                                                   .type(TypeEnum.STRING_OPTIONS)
							                                                                                   .value(List.of("SPAN", "EVENT"))
							                                                                                   .build()
						                                                 )
					                                                 )
				                                                 )
				                                                 .mapping(
					                                                 List.of(
						                                                 UnstableEvaluationRuleMapping.builder()
						                                                                              .variable("query")
						                                                                              .source(UnstableEvaluationRuleMappingSource.INPUT)
						                                                                              .build(),
						                                                 UnstableEvaluationRuleMapping.builder()
						                                                                              .variable("generation")
						                                                                              .source(UnstableEvaluationRuleMappingSource.OUTPUT)
						                                                                              .build()
					                                                 )
				                                                 )
				                                                 .build();

				try {
					var rule = this.langfuseApi.unstableEvaluationRules()
					                           .unstableEvaluationRulesCreate(APIUnstableEvaluationRulesCreateRequest.newBuilder()
					                                                                                                 .unstableCreateEvaluationRuleRequest(request)
					                                                                                                 .build());
					Log.infof("Created evaluation rule: %s", rule.getId());
					return Optional.of(rule);
				}
				catch (Exception e) {
					Log.warnf(e, "Failed to create evaluation rule: %s", e.getMessage());
					return Optional.empty();
				}
			});
	}

	private UnstableEvaluators fetchPage(int page) {
		return this.langfuseApi
			.unstableEvaluators()
			.unstableEvaluatorsList(
				APIUnstableEvaluatorsListRequest.newBuilder()
				                                .page(page)
				                                .limit(100)
				                                .build()
			);
	}

	private static Stream<UnstableEvaluator> getManagedEvaluators(UnstableEvaluators evaluators) {
		return evaluators.getData()
		                 .stream()
		                 .filter(LangfuseEvaluationInitializer::isManagedEvaluator);
	}

	private Optional<UnstableEvaluator> handleEvaluator(LlmConnection llmConnection) {
		Log.info("Checking to see if relevance evaluator is already registered");
		var scoreConfigOptional = getOrCreateContinuousScoringScoreConfig();

		scoreConfigOptional.ifPresentOrElse(
			config -> Log.info("Continuous Evaluation score config setup complete"),
			() -> Log.warn("Continuous Evaluation score config setup failed")
		);

		var firstPage = fetchPage(1);
		var totalPages = firstPage.getMeta().getTotalPages();

		return Stream.concat(
			             getManagedEvaluators(firstPage),
			             IntStream.rangeClosed(2, totalPages)
			                      .mapToObj(this::fetchPage)
			                      .flatMap(LangfuseEvaluationInitializer::getManagedEvaluators))
		             .findFirst()
		             .map(evaluator -> updateEvaluatorIfNecessary(evaluator, llmConnection))
		             .or(() -> createEvaluator(llmConnection));
	}

	private UnstableEvaluator updateEvaluatorIfNecessary(UnstableEvaluator evaluator, LlmConnection llmConnection) {
		if (isManagedEvaluator(evaluator) && (evaluator.getModelConfig() == null)) {
			var updateEvaluatorRequest = UnstableCreateEvaluatorRequest.builder()
			                                                           .name(evaluator.getName())
			                                                           .prompt(evaluator.getPrompt())
			                                                           .modelConfig(UnstableEvaluatorModelConfig.builder()
			                                                                                                    .model(llmConnection.getCustomModels().getFirst())
			                                                                                                    .provider(llmConnection.getProvider())
			                                                                                                    .build())
			                                                           .outputDefinition(getOutputDefinition(evaluator.getOutputDefinition()))
			                                                           .build();

			try {
				return this.langfuseApi
					.unstableEvaluators()
					.unstableEvaluatorsCreate(APIUnstableEvaluatorsCreateRequest.newBuilder()
					                                                            .unstableCreateEvaluatorRequest(updateEvaluatorRequest)
					                                                            .build());
			}
			catch (Exception e) {
				Log.warnf(e, "Failed to update evaluator: %s", e.getMessage());
				return null;
			}
		}

		return evaluator;
	}

	private static boolean isManagedEvaluator(UnstableEvaluator evaluator) {
		return "helpfulness".equalsIgnoreCase(evaluator.getName()) &&
			(evaluator.getType() == UnstableEvaluatorType.LLM_AS_JUDGE) &&
			(evaluator.getScope() == UnstableEvaluatorScope.MANAGED);
	}

	private Optional<UnstableEvaluator> createEvaluator(LlmConnection llmConnection) {
		Log.infof("Initializing Continuous Evaluation LLM Evaluator");

		var request = UnstableCreateEvaluatorRequest.builder()
		                                            .name("Continuous Evaluation Evaluator")
		                                            .prompt(PROMPT)
		                                            .modelConfig(UnstableEvaluatorModelConfig.builder()
		                                                                                     .model(llmConnection.getCustomModels().getFirst())
		                                                                                     .provider(llmConnection.getProvider())
		                                                                                     .build())
		                                            .outputDefinition(new UnstableEvaluatorOutputDefinition(
			                                            UnstableEvaluatorOutputDefinitionOneOf.builder()
			                                                                                  .dataType(DataTypeEnum.NUMERIC)
			                                                                                  .reasoning(UnstableEvaluatorOutputFieldDefinition.builder()
			                                                                                                                                   .description("Explain the assigned score in one concise sentence.")
			                                                                                                                                   .build())
			                                                                                  .score(UnstableEvaluatorOutputFieldDefinition.builder()
			                                                                                                                               .description("Return a numeric score between 0 and 1, where 0 means \"completely irrelevant\" and 1 means \"completely relevant\".")
			                                                                                                                               .build())
			                                                                                  .build()
		                                            ))
		                                            .build();

		try {
			var evaluator = this.langfuseApi
				.unstableEvaluators()
				.unstableEvaluatorsCreate(APIUnstableEvaluatorsCreateRequest.newBuilder()
				                                                            .unstableCreateEvaluatorRequest(request)
				                                                            .build());
			Log.infof("Registered Continuous Evaluation LLM Evaluator: %s", evaluator.getId());
			return Optional.of(evaluator);
		}
		catch (Exception e) {
			Log.warnf(e, "Failed to initialize Continuous Evaluation LLM Evaluator: %s", e.getMessage());
			return Optional.empty();
		}
	}

	private Optional<LlmConnection> getOrCreateCohereLlmConnection() {
		return getExistingLlmConnection("cohere")
			.or(() -> {
				var cohere = this.scoringConfig.cohere();
				var apiKey = cohere.apiKey()
				                   .orElseThrow(() -> new IllegalStateException("Cohere API Key must be set to initialize Cohere LLM Connection"));

				Log.infof("Initializing Cohere LLM Connection to model %s", cohere.modelName());

				var request = UpsertLlmConnectionRequest.builder()
				                                        .provider("cohere")
				                                        .adapter(LlmAdapter.OPENAI)
				                                        .baseURL(cohere.baseUrl())
				                                        .secretKey(apiKey)
				                                        .customModels(List.of(cohere.modelName()))
				                                        .build();

				return createLLMConnection(request);
			});
	}

	private Optional<ScoreConfig> getOrCreateContinuousScoringScoreConfig() {
		return getExistingScoreConfig("Continuous Evaluation Evaluator")
			.or(() -> {
				Log.info("Creating Continuous Evaluation Evaluator score config");

				var request = CreateScoreConfigRequest.builder()
				                                      .name("Continuous Evaluation Evaluator")
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
				                                .tokenizerId("openai")
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
