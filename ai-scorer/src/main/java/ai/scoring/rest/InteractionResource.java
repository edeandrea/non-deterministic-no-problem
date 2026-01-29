package ai.scoring.rest;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import ai.scoring.api.AiApi;
import ai.scoring.domain.interaction.InteractionQuery;
import ai.scoring.mapping.InteractionEventMapper;
import ai.scoring.mapping.InteractionMapper;
import ai.scoring.mapping.InteractionModeMapper;
import ai.scoring.mapping.InteractionScoreMapper;
import ai.scoring.model.InteractionEvent;
import ai.scoring.model.Interactions;
import ai.scoring.service.InteractionService;

import io.quarkus.logging.Log;

import io.smallrye.common.annotation.RunOnVirtualThread;

@RunOnVirtualThread
public class InteractionResource implements AiApi {
	private final InteractionEventMapper interactionEventMapper;
	private final InteractionModeMapper interactionModeMapper;
	private final InteractionScoreMapper interactionScoreMapper;
	private final InteractionMapper interactionMapper;
	private final InteractionService interactionService;

	public InteractionResource(InteractionEventMapper interactionEventMapper, InteractionModeMapper interactionModeMapper, InteractionScoreMapper interactionScoreMapper, InteractionMapper interactionMapper, InteractionService interactionService) {
		this.interactionEventMapper = interactionEventMapper;
		this.interactionModeMapper = interactionModeMapper;
		this.interactionScoreMapper = interactionScoreMapper;
		this.interactionMapper = interactionMapper;
		this.interactionService = interactionService;
	}

	@Override
	public Response submitInteractionEvent(@NotNull @Valid InteractionEvent event) {
		Log.infof("Received event event: %s", event);

		var interactionEvent = this.interactionEventMapper.map(event);
		var interactionMode = this.interactionModeMapper.map(event.getInteractionMode());

		return this.interactionService.handleInteractionEvent(interactionEvent, interactionMode)
			.map(this.interactionScoreMapper::map)
			.map(Response::ok)
			.orElseGet(Response::noContent)
			.build();
	}

	@Override
	public Response findInteractions(@QueryParam("applicationName") String applicationName, @QueryParam("interfaceName") String interfaceName, @QueryParam("methodName") String methodName, @QueryParam("start") Instant start, @QueryParam("end") Instant end) {
		var query = InteractionQuery.builder()
			.applicationName(applicationName)
			.interfaceName(interfaceName)
			.methodName(methodName)
			.start(start)
			.end(end)
			.build();

		var interactions = this.interactionService.findInteractions(query)
			.stream()
			.map(this.interactionMapper::map)
			.toList();

		return interactions.isEmpty() ?
		       Response.status(Status.NOT_FOUND).build() :
		       Response.ok(Interactions.builder().interactions(interactions).build()).build();
	}

	@Override
	public Response getInteractionById(UUID uuid) {
		Log.infof("Getting interaction by id: %s", uuid);
		return this.interactionService.getInteraction(uuid)
			.map(Response::ok)
			.orElseGet(() -> Response.status(Status.NOT_FOUND))
			.build();
	}
}
