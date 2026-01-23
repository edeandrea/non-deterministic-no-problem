package org.parasol.ai.scoring.resources;

import org.parasol.ai.scoring.mapping.InteractionEventMapper;
import org.parasol.ai.scoring.service.InteractionService;

import io.quarkiverse.aiinteractions.api.AiApi;
import io.quarkiverse.aiinteractions.model.InteractionEvent;

import io.quarkus.logging.Log;

import io.smallrye.common.annotation.RunOnVirtualThread;

@RunOnVirtualThread
public class InteractionResource implements AiApi {
	private final InteractionEventMapper interactionEventMapper;
	private final InteractionService interactionService;

	public InteractionResource(InteractionEventMapper interactionEventMapper, InteractionService interactionService) {
		this.interactionEventMapper = interactionEventMapper;
		this.interactionService = interactionService;
	}

	@Override
	public void submitInteractionEvent(InteractionEvent event) {
		Log.infof("Received event event: %s", event);
		this.interactionService.storeInteractionEvent(this.interactionEventMapper.map(event));
	}
}
