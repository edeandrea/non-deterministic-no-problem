package ai.scoring.rest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.core.Response;

import ai.scoring.api.AiApi;
import ai.scoring.mapping.InteractionEventMapper;
import ai.scoring.mapping.InteractionModeMapper;
import ai.scoring.mapping.InteractionScoreMapper;
import ai.scoring.model.InteractionEvent;
import ai.scoring.service.InteractionService;

import io.quarkus.logging.Log;

import io.smallrye.common.annotation.RunOnVirtualThread;

@RunOnVirtualThread
public class InteractionResource implements AiApi {
	private final InteractionEventMapper interactionEventMapper;
	private final InteractionModeMapper interactionModeMapper;
	private final InteractionScoreMapper interactionScoreMapper;
	private final InteractionService interactionService;

	public InteractionResource(InteractionEventMapper interactionEventMapper, InteractionModeMapper interactionModeMapper, InteractionScoreMapper interactionScoreMapper, InteractionService interactionService) {
		this.interactionEventMapper = interactionEventMapper;
		this.interactionModeMapper = interactionModeMapper;
		this.interactionScoreMapper = interactionScoreMapper;
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
}
