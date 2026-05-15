package ai.scoring.langfuse.init;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import org.eclipse.microprofile.rest.client.inject.RestClient;

import ai.scoring.langfuse.rest.LangfuseApiClient;
import ai.scoring.langfuse.rest.api.ModelsApi;
import ai.scoring.langfuse.rest.model.CreateModelRequest;
import ai.scoring.langfuse.rest.model.ModelUsageUnit;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class LangfuseCostModelInitializer {
	private final ModelsApi langfuseModelsApi;

	public LangfuseCostModelInitializer(@RestClient LangfuseApiClient langfuseApiClient) {
		this.langfuseModelsApi = langfuseApiClient;
	}

	void onStartup(@Observes StartupEvent event) {
		Log.info("Initializing Langfuse models");
		populateGpt5MiniModel();
	}

	private void populateGpt5MiniModel() {
		Log.info("Registering GPT-5-mini model");
		var request = new CreateModelRequest()
			.modelName("gpt-5-mini")
			.matchPattern("(?i)^(gpt-5-mini)(-.+)?$")
			.unit(ModelUsageUnit.TOKENS)
			.inputPrice(0.00000025)
			.outputPrice(0.000002)
			.tokenizerId("openai");

		try {
			var model = this.langfuseModelsApi.modelsCreate(request);
			Log.infof("Registered model in Langfuse (id=%s)", model.getId());
		}
		catch (Exception e) {
			Log.warnf(e, "Could not register model '%s' in Langfuse: %s", request.getModelName(), e.getMessage());
		}
	}
}
