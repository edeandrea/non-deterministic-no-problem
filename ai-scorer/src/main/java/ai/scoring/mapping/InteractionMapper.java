package ai.scoring.mapping;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants.ComponentModel;

import ai.scoring.domain.event.InteractionCompletedEvent;
import ai.scoring.domain.event.InteractionStartedEvent;
import ai.scoring.domain.interaction.Interaction;

@Mapper(componentModel = ComponentModel.JAKARTA_CDI, uses = InteractionScoreMapper.class)
public interface InteractionMapper {
	@Mapping(target = ".", source = "completedEvent.invocationContext")
	@Mapping(target = "result", source = "completedEvent.result")
	@Mapping(target = "systemMessage", source = "startedEvent.systemMessage")
	@Mapping(target = "userMessage", source = "startedEvent.userMessage")
	Interaction map(InteractionStartedEvent startedEvent, InteractionCompletedEvent completedEvent);

	ai.scoring.model.Interaction map(Interaction interaction);
}
