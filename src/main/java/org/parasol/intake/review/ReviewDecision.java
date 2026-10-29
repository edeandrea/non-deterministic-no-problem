package org.parasol.intake.review;

import java.util.Objects;
import java.util.Set;

import org.parasol.intake.MissingItem;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * A reviewer's decision.
 *
 * @param outcome What was decided
 * @param missingItems The items the reviewer checked as missing; only meaningful for {@link ReviewOutcome#NEEDS_INFORMATION}
 * @param fields The values the reviewer confirmed or corrected, set on the claim along with the decision; {@link ReviewedFields#none()} if none
 */
public record ReviewDecision(ReviewOutcome outcome, Set<MissingItem> missingItems, ReviewedFields fields) {
	public ReviewDecision {
		Objects.requireNonNull(outcome, "outcome");
		missingItems = (missingItems == null) ? Set.of() : Set.copyOf(missingItems);
		fields = (fields == null) ? ReviewedFields.none() : fields;
	}

	/**
	 * Whether or not this decision's outcome is {@link ReviewOutcome#READY}.
	 * @return True if this decision's outcome is {@link ReviewOutcome#READY}, false otherwise
	 */
	@JsonIgnore
	public boolean isReady() {
		return this.outcome == ReviewOutcome.READY;
	}
}
