package org.parasol.ai.scoring.mapping;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants.ComponentModel;
import org.parasol.ai.scoring.domain.event.InteractionCompletedEvent;
import org.parasol.ai.scoring.domain.event.InteractionStartedEvent;
import org.parasol.ai.scoring.domain.score.Interaction;

@Mapper(componentModel = ComponentModel.JAKARTA_CDI)
public interface InteractionMapper {
	@Mapping(target = ".", source = "completedEvent.invocationContext")
	@Mapping(target = "result", source = "completedEvent.result")
	@Mapping(target = "systemMessage", source = "startedEvent.systemMessage")
	@Mapping(target = "userMessage", source = "startedEvent.userMessage")
	@Mapping(target = "score", ignore = true)
	Interaction map(InteractionStartedEvent startedEvent, InteractionCompletedEvent completedEvent);
}
