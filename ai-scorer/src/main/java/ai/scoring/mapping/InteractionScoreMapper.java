package ai.scoring.mapping;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants.ComponentModel;

import ai.scoring.domain.interaction.InteractionScore;

@Mapper(componentModel = ComponentModel.JAKARTA_CDI)
public interface InteractionScoreMapper {
	@Mapping(target = "interactionId", source = "interaction.interactionId")
	@Mapping(target = "interactionDate", source = "interaction.interactionDate")
	ai.scoring.model.InteractionScore map(InteractionScore interactionScore);
}
