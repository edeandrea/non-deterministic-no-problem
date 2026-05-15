package ai.scoring.langfuse.init;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.validation.Valid;

import org.eclipse.microprofile.rest.client.inject.RestClient;

import ai.scoring.langfuse.config.LangfuseConfig;
import ai.scoring.langfuse.config.LangfuseConfig.Scoring;
import ai.scoring.langfuse.rest.LangfuseApiClient;
import ai.scoring.langfuse.rest.model.CreateModelRequest;
import ai.scoring.langfuse.rest.model.LlmAdapter;
import ai.scoring.langfuse.rest.model.LlmConnection;
import ai.scoring.langfuse.rest.model.Model;
import ai.scoring.langfuse.rest.model.ModelUsageUnit;
import ai.scoring.langfuse.rest.model.UnstableCreateEvaluationRuleRequest;
import ai.scoring.langfuse.rest.model.UnstableCreateEvaluatorRequest;
import ai.scoring.langfuse.rest.model.UnstableEvaluationRule;
import ai.scoring.langfuse.rest.model.UnstableEvaluationRuleEvaluatorReference;
import ai.scoring.langfuse.rest.model.UnstableEvaluationRuleMapping;
import ai.scoring.langfuse.rest.model.UnstableEvaluationRuleMappingSource;
import ai.scoring.langfuse.rest.model.UnstableEvaluationRuleTarget;
import ai.scoring.langfuse.rest.model.UnstableEvaluator;
import ai.scoring.langfuse.rest.model.UnstableEvaluatorModelConfig;
import ai.scoring.langfuse.rest.model.UnstableEvaluatorOutputDataType;
import ai.scoring.langfuse.rest.model.UnstableEvaluatorOutputDefinition;
import ai.scoring.langfuse.rest.model.UnstableEvaluatorOutputFieldDefinition;
import ai.scoring.langfuse.rest.model.UnstableEvaluatorScope;
import ai.scoring.langfuse.rest.model.UnstableEvaluatorType;
import ai.scoring.langfuse.rest.model.UnstablePublicCategoricalEvaluatorOutputScoreDefinition;
import ai.scoring.langfuse.rest.model.UpsertLlmConnectionRequest;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;

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

	private final Scoring scoringConfig;
	private final LangfuseApiClient langfuseApiClient;

	public LangfuseEvaluationInitializer(LangfuseConfig langfuseConfig, @RestClient LangfuseApiClient langfuseApiClient) {
		this.scoringConfig = langfuseConfig.scoring();
		this.langfuseApiClient = langfuseApiClient;
	}

	void onStartup(@Observes StartupEvent event) {
		if (this.scoringConfig.initializeOnStartup()) {
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

		var request = new UnstableCreateEvaluationRuleRequest()
			.name("Continuous Scoring Evaluator")
			.evaluator(new UnstableEvaluationRuleEvaluatorReference()
				.name(evaluator.getName())
				.scope(isManagedEvaluator(evaluator) ? UnstableEvaluatorScope.MANAGED : UnstableEvaluatorScope.PROJECT))
			.target(UnstableEvaluationRuleTarget.OBSERVATION)
			.enabled(true)
			.sampling(1.0)
			.mapping(List.of(
				new UnstableEvaluationRuleMapping()
					.variable("query")
					.source(UnstableEvaluationRuleMappingSource.INPUT),
				new UnstableEvaluationRuleMapping()
					.variable("generation")
					.source(UnstableEvaluationRuleMappingSource.OUTPUT)
			));

		try {
			var rule = this.langfuseApiClient.unstableEvaluationRulesCreate(request);
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

		// @TODO this should be paginated - 100 is the max per page allowed
		return this.langfuseApiClient.unstableEvaluatorsList(1, 100)
			.getData()
			.stream()
			.filter(LangfuseEvaluationInitializer::isManagedEvaluator)
			.findFirst()
			.map(evaluator -> updateEvaluatorIfNecessary(evaluator, llmConnection))
			.or(() -> createEvaluator(llmConnection));
	}

	private UnstableEvaluator updateEvaluatorIfNecessary(UnstableEvaluator evaluator, LlmConnection llmConnection) {
		if (isManagedEvaluator(evaluator) && (evaluator.getModelConfig() == null)) {
			var updateEvaluatorRequest = new UnstableCreateEvaluatorRequest()
				.name(evaluator.getName())
				.prompt(evaluator.getPrompt())
				.modelConfig(new UnstableEvaluatorModelConfig()
					.model(llmConnection.getCustomModels().getFirst())
					.provider(llmConnection.getProvider()))
				.outputDefinition(new UnstableEvaluatorOutputDefinition()
					.dataType(evaluator.getOutputDefinition().getDataType())
					.reasoning(new UnstableEvaluatorOutputFieldDefinition()
						.description(evaluator.getOutputDefinition().getReasoning().getDescription()))
					.score(new UnstablePublicCategoricalEvaluatorOutputScoreDefinition()
						.description(evaluator.getOutputDefinition().getScore().getDescription())
						.shouldAllowMultipleMatches(Optional.ofNullable(evaluator.getOutputDefinition().getScore().getShouldAllowMultipleMatches()).orElse(false))
						.categories(evaluator.getOutputDefinition().getScore().getCategories())));

			try {
				return this.langfuseApiClient.unstableEvaluatorsCreate(updateEvaluatorRequest);
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
		Log.infof("Initializing Cohere LLM Evaluator");

		var request = new UnstableCreateEvaluatorRequest()
			.name("Cohere Evaluator")
			.prompt(PROMPT)
			.modelConfig(new UnstableEvaluatorModelConfig()
				.model(llmConnection.getCustomModels().getFirst())
				.provider(llmConnection.getProvider()))
			.outputDefinition(new UnstableEvaluatorOutputDefinition()
					.dataType(UnstableEvaluatorOutputDataType.NUMERIC)
					.reasoning(new UnstableEvaluatorOutputFieldDefinition()
						.description("Explain the assigned score in one concise sentence."))
				.score(new UnstablePublicCategoricalEvaluatorOutputScoreDefinition()
					.description("Return a numeric score between 0 and 1, where 0 means \"completely irrelevant\" and 1 means \"completely relevant\".")
					.shouldAllowMultipleMatches(false)));

		try {
			var evaluator = this.langfuseApiClient.unstableEvaluatorsCreate(request);
			Log.infof("Registered Cohere LLM Evaluator: %s", evaluator.getId());
			return Optional.of(evaluator);
		}
		catch (Exception e) {
			Log.warnf(e, "Failed to initialize Cohere LLM Evaluator: %s", e.getMessage());
			return Optional.empty();
		}
	}

	private Optional<LlmConnection> createLlmConnection() {
		var cohere = this.scoringConfig.cohere();
		var apiKey = cohere.apiKey()
			.orElseThrow(() -> new IllegalStateException("Cohere API Key must be set to initialize Cohere LLM Connection"));

		Log.infof("Initializing Cohere LLM Connection to model %s", cohere.modelName());

		var request = new UpsertLlmConnectionRequest()
			.provider("cohere")
			.adapter(LlmAdapter.OPENAI)
			.baseURL(cohere.baseUrl())
			.secretKey(apiKey)
			.addCustomModelsItem(cohere.modelName());

		try {
			var connection = this.langfuseApiClient.llmConnectionsUpsert(request);
			Log.infof("Registered Cohere LLM Connection: %s", connection.getId());
			return Optional.of(connection);
		}
		catch (Exception e) {
			Log.warnf(e, "Failed to initialize Cohere LLM Connection: %s", e.getMessage());
			return Optional.empty();
		}
	}

	private Optional<Model> registerCohereModelDefinition() {
		Log.info("Registering Cohere model");
		var request = new CreateModelRequest()
			.modelName("command-r7b")
			.matchPattern("(?i)^(command-r7b)(-.+)?$")
			.unit(ModelUsageUnit.TOKENS)
			.inputPrice(0.00000004)
			.outputPrice(0.00000015)
			.tokenizerId("openai");

		try {
			var model = this.langfuseApiClient.modelsCreate(request);
			Log.infof("Registered model in Langfuse (id=%s)", model.getId());
			return Optional.of(model);
		}
		catch (Exception e) {
			Log.warnf(e, "Could not register model '%s' in Langfuse: %s", request.getModelName(), e.getMessage());
		}

		return Optional.empty();
	}
}
