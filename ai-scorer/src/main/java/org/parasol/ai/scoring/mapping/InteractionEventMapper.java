package org.parasol.ai.scoring.mapping;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants.ComponentModel;
import org.parasol.ai.scoring.domain.event.InteractionCompletedEvent;
import org.parasol.ai.scoring.domain.event.InteractionEvent;
import org.parasol.ai.scoring.domain.event.InteractionStartedEvent;
import org.parasol.ai.scoring.domain.event.InvocationContext;

@Mapper(componentModel = ComponentModel.JAKARTA_CDI)
public interface InteractionEventMapper {
	@Mapping(target = "invocationContext", source = ".")
	@Mapping(target = "id", ignore = true)
	InteractionStartedEvent map(io.quarkiverse.aiinteractions.model.InteractionStartedEvent event);

	@Mapping(target = "invocationContext", source = ".")
	@Mapping(target = "id", ignore = true)
	InteractionCompletedEvent map(io.quarkiverse.aiinteractions.model.InteractionCompletedEvent event);

	InvocationContext mapInvocationContext(io.quarkiverse.aiinteractions.model.InteractionEvent event);

	default InteractionEvent map(io.quarkiverse.aiinteractions.model.InteractionEvent event) {
		return switch(event) {
			case io.quarkiverse.aiinteractions.model.InteractionStartedEvent startedEvent -> map(startedEvent);
			case io.quarkiverse.aiinteractions.model.InteractionCompletedEvent completedEvent -> map(completedEvent);
			default -> throw new IllegalArgumentException("Unsupported event type: " + event.getClass().getName());
		};
	}
}
