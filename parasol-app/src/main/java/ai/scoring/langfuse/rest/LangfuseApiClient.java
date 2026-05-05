package ai.scoring.langfuse.rest;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import ai.scoring.langfuse.rest.model.ModelsApi;

@Path("/api/public")
@RegisterRestClient(configKey = "langfuse-api")
@RegisterProvider(LangfuseAuthRequestFilter.class)
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface LangfuseApiClient extends ModelsApi {

}
