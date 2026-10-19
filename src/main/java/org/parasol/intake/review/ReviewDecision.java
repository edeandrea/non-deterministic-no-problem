package org.parasol.intake.review;

import java.util.Objects;
import java.util.Set;

import org.parasol.intake.MissingItem;

/**
 * A reviewer's decision.
 *
 * @param outcome What was decided
 * @param missingItems The items the reviewer ticked as missing; only meaningful for {@link ReviewOutcome#NEEDS_INFORMATION}
 */
public record ReviewDecision(ReviewOutcome outcome, Set<MissingItem> missingItems) {
	public ReviewDecision {
		Objects.requireNonNull(outcome, "outcome");
		missingItems = (missingItems == null) ? Set.of() : Set.copyOf(missingItems);
	}
}
