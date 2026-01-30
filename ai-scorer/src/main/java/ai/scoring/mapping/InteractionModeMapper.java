package ai.scoring.mapping;

import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants.ComponentModel;

import ai.scoring.domain.interaction.InteractionMode;

@Mapper(componentModel = ComponentModel.JAKARTA_CDI)
public interface InteractionModeMapper {
	InteractionMode map(ai.scoring.model.InteractionMode interactionMode);
}
