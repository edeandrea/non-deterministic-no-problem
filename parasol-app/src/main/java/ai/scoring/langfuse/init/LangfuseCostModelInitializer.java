package ai.scoring.langfuse.init;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;

import ai.scoring.langfuse.config.LangfuseConfig;
import com.langfuse.api.LangfuseApi;
import com.langfuse.api.model.CreateModelRequest;
import com.langfuse.api.model.ModelUsageUnit;
import com.langfuse.api.models.ModelsApi;
import com.langfuse.api.models.ModelsApi.APIModelsCreateRequest;

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

	private void populateGpt5MiniModel() {
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
			var model = this.langfuseModelsApi.modelsCreate(
				APIModelsCreateRequest.newBuilder()
				                      .createModelRequest(request)
				                      .build());
			Log.infof("Registered model in Langfuse (id=%s)", model.getId());
		}
		catch (Exception e) {
			Log.warnf(e, "Could not register model '%s' in Langfuse: %s", request.getModelName(), e.getMessage());
		}
	}
}
