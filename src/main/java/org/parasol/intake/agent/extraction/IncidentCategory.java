package org.parasol.intake.agent.extraction;

import java.util.Optional;

import org.parasol.claim.model.ClaimCategory;

import dev.langchain4j.model.output.structured.Description;

/**
 * #221 spike: the category options a decision model chooses from. Unlike {@link ClaimCategory} it has an option for
 * "nothing says what happened", since a choice question always picks one of its options.
 */
public enum IncidentCategory {
	@Description("only the customer's own vehicle was involved, for example it hit a pole, a wall, a guardrail or an animal, or went off the road")
	SINGLE_VEHICLE,
	@Description("the customer's vehicle collided with, or was hit by, another vehicle")
	MULTIPLE_VEHICLE,
	@Description("the vehicle, its parts or things inside it were stolen")
	THEFT,
	@Description("damage from anything else, such as hail, a storm, a flood, a fire, a falling object, a thrown rock or vandalism")
	OTHER,
	@Description("the correspondence doesn't say what happened, only that something happened or that a claim is being made")
	NOT_STATED;

	public Optional<ClaimCategory> toClaimCategory() {
		return (this == NOT_STATED) ? Optional.empty() : Optional.of(ClaimCategory.valueOf(name()));
	}
}
