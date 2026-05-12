package ai.scoring.langfuse.rest;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status.Family;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import ai.scoring.langfuse.rest.model.ModelsApi;

import io.quarkus.logging.Log;
import io.quarkus.rest.client.reactive.ClientBasicAuth;
import io.quarkus.rest.client.reactive.ClientExceptionMapper;

@Path("/api/public")
@RegisterRestClient(configKey = "langfuse-api")
@ClientBasicAuth(username = "${quarkus.aiscoring.langfuse.public-key}", password = "${quarkus.aiscoring.langfuse.secret-key}")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface LangfuseApiClient extends ModelsApi {
	@ClientExceptionMapper
	static RuntimeException toException(Response response) {
		var message = "Langfuse API error (%d): %s".formatted(response.getStatus(), response.readEntity(String.class));

		if (response.getStatusInfo().getFamily() == Family.CLIENT_ERROR) {
			Log.warn(message);
			return null;
		}

		return new RuntimeException(message);
	}
}
