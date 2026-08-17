package ai.scoring.langfuse.init;

import java.util.Optional;

import io.quarkus.logging.Log;

import com.langfuse.api.LangfuseApi;
import com.langfuse.api.llmConnections.LlmConnectionsApi.APILlmConnectionsListRequest;
import com.langfuse.api.llmConnections.LlmConnectionsApi.APILlmConnectionsUpsertRequest;
import com.langfuse.api.model.CreateModelRequest;
import com.langfuse.api.model.LlmConnection;
import com.langfuse.api.model.Model;
import com.langfuse.api.model.ScoreConfig;
import com.langfuse.api.model.UnstableEvaluationRule;
import com.langfuse.api.model.UnstableEvaluatorOutputDefinition;
import com.langfuse.api.model.UnstableEvaluatorOutputDefinitionOneOf;
import com.langfuse.api.model.UnstableEvaluatorOutputDefinitionOneOf1;
import com.langfuse.api.model.UnstableEvaluatorOutputDefinitionOneOf2;
import com.langfuse.api.model.UnstablePublicEvaluatorOutputDefinition;
import com.langfuse.api.model.UpsertLlmConnectionRequest;
import com.langfuse.api.models.ModelsApi.APIModelsCreateRequest;
import com.langfuse.api.models.ModelsApi.APIModelsListRequest;
import com.langfuse.api.scoreConfigs.ScoreConfigsApi.APIScoreConfigsGetRequest;
import com.langfuse.api.unstableEvaluationRules.UnstableEvaluationRulesApi.APIUnstableEvaluationRulesListRequest;

public abstract sealed class LangfuseInitializer permits LangfuseEvaluationInitializer {
	protected final LangfuseApi langfuseApi;

	protected LangfuseInitializer(LangfuseApi langfuseApi) {
		this.langfuseApi = langfuseApi;
	}

	protected Optional<LlmConnection> getExistingLlmConnection(String provider) {
		try {
			return this.langfuseApi
				.llmConnections()
				.llmConnectionsList(APILlmConnectionsListRequest.newBuilder().build())
				.getData()
				.stream()
				.filter(conn -> provider.equalsIgnoreCase(conn.getProvider()))
				.findFirst();
		}
		catch (Exception ex) {
			return Optional.empty();
		}
	}

	protected Optional<UnstableEvaluationRule> getExistingEvaluationRule(String name) {
		try {
			return this.langfuseApi
				.unstableEvaluationRules()
				.unstableEvaluationRulesList(APIUnstableEvaluationRulesListRequest.newBuilder().build())
				.getData()
				.stream()
				.filter(readable -> readable.getActualInstance() instanceof UnstableEvaluationRule)
				.map(readable -> (UnstableEvaluationRule) readable.getActualInstance())
				.filter(rule -> name.equalsIgnoreCase(rule.getName()))
				.findFirst();
		}
		catch (Exception ex) {
			return Optional.empty();
		}
	}

	protected Optional<ScoreConfig> getExistingScoreConfig(String name) {
		try {
			return this.langfuseApi
				.scoreConfigs()
				.scoreConfigsGet(APIScoreConfigsGetRequest.newBuilder().build())
				.getData()
				.stream()
				.filter(config -> name.equalsIgnoreCase(config.getName()))
				.findFirst();
		}
		catch (Exception ex) {
			return Optional.empty();
		}
	}

	protected Optional<Model> getExistingModelDefinition(String modelName) {
		try {
			return this.langfuseApi
				.models()
				.modelsList(APIModelsListRequest.newBuilder().build())
				.getData()
				.stream()
				.filter(model -> modelName.equalsIgnoreCase(model.getModelName()))
				.findFirst();
		}
		catch (Exception ex) {
			return Optional.empty();
		}
	}

	protected Optional<Model> createModel(CreateModelRequest request) {
		try {
			var model = this.langfuseApi.models().modelsCreate(APIModelsCreateRequest.newBuilder()
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

	protected Optional<LlmConnection> createLLMConnection(UpsertLlmConnectionRequest request) {
		try {
			var connection = this.langfuseApi.llmConnections()
			                                 .llmConnectionsUpsert(APILlmConnectionsUpsertRequest.newBuilder()
			                                                                                     .upsertLlmConnectionRequest(request)
			                                                                                     .build());
			Log.infof("Registered Cohere LLM Connection: %s", connection.getId());
			return Optional.of(connection);
		}
		catch (Exception e) {
			Log.warnf(e, "Failed to initialize Cohere LLM Connection: %s", e.getMessage());
		}

		return Optional.empty();
	}

	protected static UnstableEvaluatorOutputDefinition getOutputDefinition(UnstablePublicEvaluatorOutputDefinition outputDefinition) {
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
}
