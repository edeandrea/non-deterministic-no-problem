package ai.scoring.mapping;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants.ComponentModel;

import ai.scoring.domain.event.InteractionCompletedEvent;
import ai.scoring.domain.event.InteractionEvent;
import ai.scoring.domain.event.InteractionStartedEvent;
import ai.scoring.domain.event.InvocationContext;

@Mapper(componentModel = ComponentModel.JAKARTA_CDI)
public interface InteractionEventMapper {
	@Mapping(target = "invocationContext", source = ".")
	@Mapping(target = "id", ignore = true)
	InteractionStartedEvent map(ai.scoring.model.InteractionStartedEvent event);

	@Mapping(target = "invocationContext", source = ".")
	@Mapping(target = "id", ignore = true)
	InteractionCompletedEvent map(ai.scoring.model.InteractionCompletedEvent event);

	InvocationContext mapInvocationContext(ai.scoring.model.InteractionEvent event);

	default InteractionEvent map(ai.scoring.model.InteractionEvent event) {
		return switch(event) {
			case ai.scoring.model.InteractionStartedEvent startedEvent -> map(startedEvent);
			case ai.scoring.model.InteractionCompletedEvent completedEvent -> map(completedEvent);
			default -> throw new IllegalArgumentException("Unsupported event type: " + event.getClass().getName());
		};
	}
}
