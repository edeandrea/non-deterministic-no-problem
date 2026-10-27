package org.parasol.intake.review.rest;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import org.parasol.claim.model.ClaimCategory;
import org.parasol.intake.MissingItem;
import org.parasol.intake.review.ReviewDecision;
import org.parasol.intake.review.ReviewOutcome;
import org.parasol.intake.review.ReviewedFields;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * The body of {@code POST /api/db/claims/{id}/review-decision}: the decision, and the claim's fields as the reviewer
 * confirmed or corrected them. Every field the reviewer doesn't ask the customer for must have a value, and none they ask
 * for may have one ({@link ValidReviewDecision}).
 *
 * @param outcome what the reviewer decided
 * @param missingItems the items the reviewer asks the customer for; must be non-empty for
 *                     {@link ReviewOutcome#NEEDS_INFORMATION} and empty for {@link ReviewOutcome#READY}
 * @param fields the claim's fields as the reviewer confirmed or corrected them
 * @param version the {@code ClaimReviewState} version the reviewer saw, for the optimistic lock; required, so a client
 *                can't skip the lock by leaving it out
 */
@JsonNaming(SnakeCaseStrategy.class)
@ValidReviewDecision
public record ReviewDecisionRequest(@NotNull ReviewOutcome outcome, Set<MissingItem> missingItems, @NotNull @Valid Fields fields, @NotNull Long version) {
	/**
	 * The longest incident description: the claim's summary column, which stores it.
	 */
	public static final int MAX_DESCRIPTION_LENGTH = 5000;

	/**
	 * The longest incident location.
	 */
	public static final int MAX_LOCATION_LENGTH = 1000;

	public ReviewDecisionRequest {
		missingItems = (missingItems == null) ? Set.of() : Set.copyOf(missingItems);
	}

	/**
	 * This request as the decision the service applies.
	 *
	 * @return the decision
	 */
	public ReviewDecision toDecision() {
		return new ReviewDecision(this.outcome, this.missingItems, this.fields.toReviewedFields());
	}

	/**
	 * The claim's fields as the reviewer confirmed or corrected them. A blank text counts as no value.
	 *
	 * @param description the incident description (the claim's summary)
	 * @param incidentDate the incident date; not in the future
	 * @param incidentTime the incident time, if known; only with an incident date
	 * @param location the incident location
	 * @param category the claim category, as its label or constant name
	 */
	@JsonNaming(SnakeCaseStrategy.class)
	public record Fields(
		@Size(max = MAX_DESCRIPTION_LENGTH) String description,
		@PastOrPresent LocalDate incidentDate,
		LocalTime incidentTime,
		@Size(max = MAX_LOCATION_LENGTH) String location,
		ClaimCategory category) {

		public Fields {
			description = stripToNull(description);
			location = stripToNull(location);
		}

		/**
		 * These fields as the values the decision sets on the claim.
		 *
		 * @return the reviewed fields
		 */
		public ReviewedFields toReviewedFields() {
			return new ReviewedFields(this.description, this.incidentDate, this.incidentTime, this.location, this.category);
		}

		private static String stripToNull(String value) {
			return Optional.ofNullable(value)
				.map(String::strip)
				.filter(v -> !v.isEmpty())
				.orElse(null);
		}
	}
}
