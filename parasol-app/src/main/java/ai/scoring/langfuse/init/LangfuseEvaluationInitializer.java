package ai.scoring.langfuse.init;

import static ai.scoring.langfuse.session.SessionSentiment.Sentiment;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;

import ai.scoring.langfuse.config.LangfuseConfig;
import ai.scoring.langfuse.config.LangfuseConfig.Evaluation;
import ai.scoring.langfuse.session.SessionSentiment;
import com.langfuse.api.LangfuseApi;
import com.langfuse.api.llmConnections.LlmConnectionsApi.APILlmConnectionsUpsertRequest;
import com.langfuse.api.model.ConfigCategory;
import com.langfuse.api.model.CreateModelRequest;
import com.langfuse.api.model.CreateScoreConfigRequest;
import com.langfuse.api.model.LlmAdapter;
import com.langfuse.api.model.LlmConnection;
import com.langfuse.api.model.Model;
import com.langfuse.api.model.ModelUsageUnit;
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
import com.langfuse.api.model.UnstableEvaluatorOutputDefinitionOneOf1;
import com.langfuse.api.model.UnstableEvaluatorOutputDefinitionOneOf2;
import com.langfuse.api.model.UnstableEvaluatorOutputFieldDefinition;
import com.langfuse.api.model.UnstableEvaluatorScope;
import com.langfuse.api.model.UnstableEvaluatorType;
import com.langfuse.api.model.UnstablePublicEvaluatorOutputDefinition;
import com.langfuse.api.model.UpsertLlmConnectionRequest;
import com.langfuse.api.models.ModelsApi.APIModelsCreateRequest;
import com.langfuse.api.scoreConfigs.ScoreConfigsApi.APIScoreConfigsCreateRequest;
import com.langfuse.api.unstableEvaluationRules.UnstableEvaluationRulesApi.APIUnstableEvaluationRulesCreateRequest;
import com.langfuse.api.unstableEvaluators.UnstableEvaluatorsApi.APIUnstableEvaluatorsCreateRequest;
import com.langfuse.api.unstableEvaluators.UnstableEvaluatorsApi.APIUnstableEvaluatorsListRequest;

@ApplicationScoped
public class LangfuseEvaluationInitializer {
	private static final String PROMPT = """
		You are an AI evaluating a response and the expected output.
		You need to evaluate whether the response is relevant to the question.
		
		---
		Input: {{query}}
		
		---
		Output: {{generation}}
		""";

	private final Evaluation scoringConfig;
	private final LangfuseApi langfuseApi;

	public LangfuseEvaluationInitializer(LangfuseConfig langfuseConfig, LangfuseApi langfuseApi) {
		this.scoringConfig = langfuseConfig.evaluation();
		this.langfuseApi = langfuseApi;
	}

	void onStartup(@Observes StartupEvent event) {
		if (this.scoringConfig.initializeOnStartup()) {
			createSessionSentimentScoreConfig()
				.ifPresentOrElse(
					sessionScore -> Log.info("Session Evaluation config set up"),
					() -> Log.warn("Session scoring config setup failed")
				);

			registerCohereModelDefinition()
				.flatMap(model -> createLlmConnection())
				.flatMap(this::handleEvaluator)
				.flatMap(this::createEvaluationRule)
				.ifPresentOrElse(
					rule -> Log.info("LLM Evaluation set up"),
					() -> Log.warn("LLM Evaluation setup failed")
				);
		}
	}

	private Optional<UnstableEvaluationRule> createEvaluationRule(UnstableEvaluator evaluator) {
		Log.infof("Creating evaluation rule for evaluator %s", evaluator.getName());

		var request = UnstableCreateEvaluationRuleRequest.builder()
			.name("Continuous Evaluation Evaluator")
			.evaluator(UnstableEvaluationRuleEvaluatorReference.builder()
				.name(evaluator.getName())
				.scope(isManagedEvaluator(evaluator) ? UnstableEvaluatorScope.MANAGED : UnstableEvaluatorScope.PROJECT)
				.build())
			.target(UnstableEvaluationRuleTarget.OBSERVATION)
			.enabled(true)
			.sampling(1.0)
			.filter(List.of(
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
			))
			.mapping(List.of(
				UnstableEvaluationRuleMapping.builder()
					.variable("query")
					.source(UnstableEvaluationRuleMappingSource.INPUT)
					.build(),
				UnstableEvaluationRuleMapping.builder()
					.variable("generation")
					.source(UnstableEvaluationRuleMappingSource.OUTPUT)
					.build()
			))
			.build();

		try {
			var rule = this.langfuseApi
				.unstableEvaluationRules()
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
	}

	private Optional<UnstableEvaluator> handleEvaluator(LlmConnection llmConnection) {
		Log.info("Checking to see if relevance evaluator is already registered");
		var scoreConfigOptional = createContinuousScoringScoreConfig();

		scoreConfigOptional.ifPresentOrElse(
				config -> Log.info("Continuous Evaluation score config setup complete"),
				() -> Log.warn("Continuous Evaluation score config setup failed")
			);

		// @TODO this should be paginated - 100 is the max per page allowed
		return this.langfuseApi
			.unstableEvaluators()
			.unstableEvaluatorsList(APIUnstableEvaluatorsListRequest.newBuilder()
				.page(1)
				.limit(100)
				.build())
			.getData()
			.stream()
     .filter(LangfuseEvaluationInitializer::isManagedEvaluator)
     .findFirst()
     .map(evaluator -> updateEvaluatorIfNecessary(evaluator, llmConnection))
     .or(() -> createEvaluator(llmConnection));
	}

	private static UnstableEvaluatorOutputDefinition getOutputDefinition(UnstablePublicEvaluatorOutputDefinition outputDefinition) {
		return switch (outputDefinition.getActualInstance()) {
			case UnstableEvaluatorOutputDefinitionOneOf oneOf ->
				new UnstableEvaluatorOutputDefinition(
					UnstableEvaluatorOutputDefinitionOneOf.builder()
						.dataType(oneOf.getDataType())
						.reasoning(oneOf.getReasoning())
						.score(oneOf.getScore())
						.build()
				);

			case UnstableEvaluatorOutputDefinitionOneOf1 oneOf1 ->
				new UnstableEvaluatorOutputDefinition(
					UnstableEvaluatorOutputDefinitionOneOf1.builder()
						.dataType(oneOf1.getDataType())
						.reasoning(oneOf1.getReasoning())
						.score(oneOf1.getScore())
						.build()
				);

			case UnstableEvaluatorOutputDefinitionOneOf2 oneOf2 ->
				new UnstableEvaluatorOutputDefinition(
					UnstableEvaluatorOutputDefinitionOneOf2.builder()
						.dataType(oneOf2.getDataType())
						.reasoning(oneOf2.getReasoning())
						.score(oneOf2.getScore())
						.build()
				);

			default -> throw new IllegalStateException("Unexpected output definition type: " + outputDefinition.getActualInstance());
		};
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

	private Optional<LlmConnection> createLlmConnection() {
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

		try {
			var connection = this.langfuseApi
				.llmConnections()
				.llmConnectionsUpsert(APILlmConnectionsUpsertRequest.newBuilder()
					.upsertLlmConnectionRequest(request)
					.build());
			Log.infof("Registered Cohere LLM Connection: %s", connection.getId());
			return Optional.of(connection);
		}
		catch (Exception e) {
			Log.warnf(e, "Failed to initialize Cohere LLM Connection: %s", e.getMessage());
			return Optional.empty();
		}
	}

	private Optional<ScoreConfig> createContinuousScoringScoreConfig() {
		Log.info("Creating Continuous Evaluation Evaluator score config");

		var request = CreateScoreConfigRequest.builder()
			.name("Continuous Evaluation Evaluator")
			.dataType(ScoreConfigDataType.NUMERIC)
			.minValue(0.0)
			.maxValue(1.0)
			.description("Relevance score for individual AI responses. 0 = completely irrelevant, 1 = completely relevant.")
			.build();

		try {
			var config = this.langfuseApi
				.scoreConfigs()
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
	}

	private Optional<ScoreConfig> createSessionSentimentScoreConfig() {
		Log.info("Creating session-sentiment score config");

		var categories = Arrays.stream(Sentiment.values())
			.map(s ->
				ConfigCategory.builder()
					.label(s.label())
					.value(s.value())
					.build()
			)
			.toList();

		var request = CreateScoreConfigRequest.builder()
			.name(SessionSentiment.SCORE_NAME)
			.dataType(ScoreConfigDataType.CATEGORICAL)
			.categories(categories)
			.description("Overall user sentiment for the conversation session. Evaluates whether the user's queries were answered, if they left satisfied, or if they appeared frustrated.")
			.build();

		try {
			var config = this.langfuseApi
				.scoreConfigs()
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
	}

	private Optional<Model> registerCohereModelDefinition() {
		Log.info("Registering Cohere model");
		var request = CreateModelRequest.builder()
			.modelName("command-r7b")
			.matchPattern("(?i)^(command-r7b)(-.+)?$")
			.unit(ModelUsageUnit.TOKENS)
			.inputPrice(0.00000004)
			.outputPrice(0.00000015)
			.tokenizerId("openai")
			.build();

		try {
			var model = this.langfuseApi
				.models()
				.modelsCreate(APIModelsCreateRequest.newBuilder()
					.createModelRequest(request)
					.build());
			Log.infof("Registered model in Langfuse (id=%s)", model.getId());
			return Optional.of(model);
		}
		catch (Exception e) {
			Log.warnf(e, "Could not register model '%s' in Langfuse: %s", request.getModelName(), e.getMessage());
		}

		return Optional.empty();
	}
}
