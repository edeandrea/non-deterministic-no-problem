package org.parasol.ai.testing.mapping;

import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants.ComponentModel;
import org.parasol.ai.testing.domain.jpa.Interaction;
import org.parasol.aiinteractions.model.InteractionsInteractionsInner;

@Mapper(componentModel = ComponentModel.JAKARTA_CDI)
public interface InteractionMapper {
	Interaction map(InteractionsInteractionsInner interaction, String interactionUri);
}
