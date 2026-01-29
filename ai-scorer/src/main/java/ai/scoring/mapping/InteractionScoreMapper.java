package ai.scoring.mapping;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants.ComponentModel;

import ai.scoring.domain.interaction.InteractionScore;

@Mapper(componentModel = ComponentModel.JAKARTA_CDI, uses = InteractionModeMapper.class)
public interface InteractionScoreMapper {
	@Mapping(target = "interactionId", source = "interaction.interactionId")
	@Mapping(target = "interactionDate", source = "interaction.interactionDate")
	@Mapping(target = "interactionMode", source = "mode")
	ai.scoring.model.InteractionScore map(InteractionScore interactionScore);
}
