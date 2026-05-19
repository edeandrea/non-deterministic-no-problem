package ai.scoring.langfuse.rest;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status.Family;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import io.quarkus.logging.Log;
import io.quarkus.rest.client.reactive.ClientBasicAuth;
import io.quarkus.rest.client.reactive.ClientExceptionMapper;

import ai.scoring.langfuse.rest.api.LegacyScoreV1Api;
import ai.scoring.langfuse.rest.api.LlmConnectionsApi;
import ai.scoring.langfuse.rest.api.ModelsApi;
import ai.scoring.langfuse.rest.api.ScoreConfigsApi;
import ai.scoring.langfuse.rest.api.SessionsApi;
import ai.scoring.langfuse.rest.api.UnstableEvaluationRulesApi;
import ai.scoring.langfuse.rest.api.UnstableEvaluatorsApi;

@Path("/")
@RegisterRestClient(configKey = "langfuse-api")
@ClientBasicAuth(username = "${quarkus.aiscoring.langfuse.public-key}", password = "${quarkus.aiscoring.langfuse.secret-key}")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface LangfuseApiClient extends LegacyScoreV1Api, LlmConnectionsApi, ModelsApi, ScoreConfigsApi, SessionsApi, UnstableEvaluationRulesApi, UnstableEvaluatorsApi {
	@ClientExceptionMapper
	static RuntimeException toException(Response response) {
		var message = "Langfuse API error (%d): %s".formatted(response.getStatus(), response.readEntity(String.class));

		if (response.getStatus() == 404) {
			return new LangfuseNotFoundException(message);
		}

		if (response.getStatusInfo().getFamily() == Family.CLIENT_ERROR) {
			Log.warn(message);
			return null;
		}

		return new RuntimeException(message);
	}
}
