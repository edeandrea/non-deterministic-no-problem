package ai.scoring.langfuse.init;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;

import ai.scoring.langfuse.config.LangfuseConfig;
import io.quarkiverse.langfuse.client.LangfuseNotFoundException;
import com.langfuse.api.LangfuseApi;
import com.langfuse.api.model.CreateModelRequest;
import com.langfuse.api.model.ModelUsageUnit;
import com.langfuse.api.models.ModelsApi;
import com.langfuse.api.models.ModelsApi.APIModelsCreateRequest;
import com.langfuse.api.models.ModelsApi.APIModelsListRequest;

@ApplicationScoped
@UnlessBuildProfile("test")
public class LangfuseCostModelInitializer {
	private final ModelsApi langfuseModelsApi;
	private final LangfuseConfig langfuseConfig;

	public LangfuseCostModelInitializer(LangfuseApi langfuseApi, LangfuseConfig langfuseConfig) {
		this.langfuseModelsApi = langfuseApi.models();
		this.langfuseConfig = langfuseConfig;
	}

	void onStartup(@Observes StartupEvent event) {
		if (this.langfuseConfig.evaluation().initializeOnStartup()) {
			Log.info("Initializing Langfuse models");
			populateGpt5MiniModel();
		}
	}

	private boolean isModelRegistered(String modelName) {
		try {
			return this.langfuseModelsApi.modelsList(APIModelsListRequest.newBuilder().build())
				.getData()
				.stream()
				.anyMatch(model -> modelName.equalsIgnoreCase(model.getModelName()));
		}
		catch (Exception ex) {
			return false;
		}
	}

	private void populateGpt5MiniModel() {
		if (!isModelRegistered("gpt-5-mini")) {
			Log.info("Registering GPT-5-mini model");
			var request = CreateModelRequest.builder()
			                                .modelName("gpt-5-mini")
			                                .matchPattern("(?i)^(gpt-5-mini)(-.+)?$")
			                                .unit(ModelUsageUnit.TOKENS)
			                                .inputPrice(0.00000025)
			                                .outputPrice(0.000002)
			                                .tokenizerId("openai")
			                                .build();

			try {
				var model = this.langfuseModelsApi.modelsCreate(APIModelsCreateRequest.newBuilder()
				                                                                      .createModelRequest(request)
				                                                                      .build());
				Log.infof("Registered model in Langfuse (id=%s)", model.getId());
			}
			catch (Exception e) {
				Log.warnf(e, "Could not register model '%s' in Langfuse: %s", request.getModelName(), e.getMessage());
			}
		}
	}
}
