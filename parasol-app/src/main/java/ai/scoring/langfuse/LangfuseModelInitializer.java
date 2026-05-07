package ai.scoring.langfuse;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import org.eclipse.microprofile.rest.client.inject.RestClient;

import ai.scoring.langfuse.rest.LangfuseApiClient;
import ai.scoring.langfuse.rest.model.ModelUsageUnit;
import ai.scoring.langfuse.rest.model.request.CreateModelRequest;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class LangfuseModelInitializer {
	private final LangfuseApiClient langfuseApiClient;

	public LangfuseModelInitializer(@RestClient LangfuseApiClient langfuseApiClient) {
		this.langfuseApiClient = langfuseApiClient;
	}

	void onStartup(@Observes StartupEvent event) {
		Log.info("Initializing Langfuse model");
		var request = CreateModelRequest.builder()
			.modelName("gpt-5-mini")
			.matchPattern("(?i)^(gpt-5-mini)(-.+)?$")
			.unit(ModelUsageUnit.TOKENS)
			.inputPrice(0.00000025)
			.outputPrice(0.000002)
			.tokenizerId("openai")
			.build();

		this.langfuseApiClient.createModelAsync(request)
			.invoke(created -> Log.infof("Registered model in Langfuse (id=%s)", created.id()))
			.onFailure().invoke(e -> Log.warnf(e, "Could not register model '%s' in Langfuse (may not be available yet)", request.modelName()))
			.await()
			.atMost(Duration.ofSeconds(30));
	}
}
