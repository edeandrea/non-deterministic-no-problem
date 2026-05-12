package ai.scoring.langfuse.rest.model;

import java.time.temporal.ChronoUnit;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

import org.eclipse.microprofile.faulttolerance.Retry;

import ai.scoring.langfuse.rest.model.request.CreateModelRequest;
import ai.scoring.langfuse.rest.model.response.Model;
import io.smallrye.mutiny.Uni;

public interface ModelsApi {
	@POST
	@Path("/models")
	@Retry(delay = 1, delayUnit = ChronoUnit.SECONDS, jitter = 500, maxRetries = 5)
	Model createModel(CreateModelRequest model);

	@POST
	@Path("/models")
	@Retry(delay = 1, delayUnit = ChronoUnit.SECONDS, jitter = 500, maxRetries = 5)
	Uni<Model> createModelAsync(CreateModelRequest model);
}