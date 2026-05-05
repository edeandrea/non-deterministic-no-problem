package ai.scoring.langfuse.rest.model;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

import ai.scoring.langfuse.rest.model.request.CreateModelRequest;
import ai.scoring.langfuse.rest.model.response.Model;

public interface ModelsApi {
	@POST
	@Path("/models")
	Model createModel(CreateModelRequest model);
}