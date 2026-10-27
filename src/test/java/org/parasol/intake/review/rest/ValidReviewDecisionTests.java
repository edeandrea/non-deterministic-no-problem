package org.parasol.intake.review.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.parasol.claim.model.ClaimCategory;
import org.parasol.intake.MissingItem;
import org.parasol.intake.review.ReviewOutcome;
import org.parasol.intake.review.rest.ReviewDecisionRequest.Fields;

class ValidReviewDecisionTests {
	private static final Fields ALL_FIELDS = new Fields("Car hit a post.", LocalDate.of(2026, 10, 7), LocalTime.of(15, 30), "Elm Street", ClaimCategory.SINGLE_VEHICLE);

	@Test
	void readyWithEveryFieldIsValid() {
		assertThat(problems(ReviewOutcome.READY, Set.of(), ALL_FIELDS)).isEmpty();
	}

	@Test
	void readyWithoutATimeIsValid() {
		assertThat(problems(ReviewOutcome.READY, Set.of(), new Fields("Car hit a post.", LocalDate.of(2026, 10, 7), null, "Elm Street", ClaimCategory.SINGLE_VEHICLE)))
			.isEmpty();
	}

	@Test
	void readyAskingForAnItemIsInvalid() {
		assertThat(problems(ReviewOutcome.READY, Set.of(MissingItem.LOCATION), new Fields("Car hit a post.", LocalDate.of(2026, 10, 7), null, null, ClaimCategory.SINGLE_VEHICLE)))
			.containsExactly("missing_items must be empty for READY");
	}

	@Test
	void readyMissingAFieldIsInvalid() {
		assertThat(problems(ReviewOutcome.READY, Set.of(), new Fields("Car hit a post.", LocalDate.of(2026, 10, 7), null, null, null)))
			.containsExactly("location needs a value unless it's asked of the customer", "category needs a value unless it's asked of the customer");
	}

	@Test
	void aBlankTextIsNoValue() {
		assertThat(problems(ReviewOutcome.READY, Set.of(), new Fields("  ", LocalDate.of(2026, 10, 7), null, "\t", ClaimCategory.SINGLE_VEHICLE)))
			.containsExactly("incident description needs a value unless it's asked of the customer", "location needs a value unless it's asked of the customer");
	}

	@Test
	void needsInformationWithTheAskedFieldsLeftOutIsValid() {
		assertThat(problems(ReviewOutcome.NEEDS_INFORMATION, Set.of(MissingItem.LOCATION, MissingItem.INCIDENT_DATE), new Fields("Car hit a post.", null, null, null, ClaimCategory.OTHER)))
			.isEmpty();
	}

	@Test
	void needsInformationWithNoItemsIsInvalid() {
		assertThat(problems(ReviewOutcome.NEEDS_INFORMATION, Set.of(), ALL_FIELDS))
			.containsExactly("missing_items must not be empty for NEEDS_INFORMATION");
	}

	@Test
	void anAskedFieldWithAValueIsInvalid() {
		assertThat(problems(ReviewOutcome.NEEDS_INFORMATION, Set.of(MissingItem.LOCATION), ALL_FIELDS))
			.containsExactly("location is asked of the customer, so it can't also have a value");
	}

	@Test
	void aFieldNeitherGivenNorAskedIsInvalid() {
		assertThat(problems(ReviewOutcome.NEEDS_INFORMATION, Set.of(MissingItem.LOCATION), new Fields(null, LocalDate.of(2026, 10, 7), null, null, ClaimCategory.OTHER)))
			.containsExactly("incident description needs a value unless it's asked of the customer");
	}

	@Test
	void aTimeWithoutADateIsInvalid() {
		assertThat(problems(ReviewOutcome.NEEDS_INFORMATION, Set.of(MissingItem.INCIDENT_DATE), new Fields("Car hit a post.", null, LocalTime.NOON, "Elm Street", ClaimCategory.OTHER)))
			.containsExactly("incident_time needs an incident_date");
	}

	@Test
	void everyProblemIsReported() {
		assertThat(problems(ReviewOutcome.READY, Set.of(MissingItem.CATEGORY), new Fields(null, null, LocalTime.NOON, "Elm Street", ClaimCategory.OTHER)))
			.containsExactly(
				"missing_items must be empty for READY",
				"incident description needs a value unless it's asked of the customer",
				"incident date needs a value unless it's asked of the customer",
				"category is asked of the customer, so it can't also have a value",
				"incident_time needs an incident_date");
	}

	@Test
	void aMissingOutcomeOrFieldsIsLeftToNotNull() {
		assertThat(problems(null, Set.of(MissingItem.LOCATION), ALL_FIELDS)).isEmpty();
		assertThat(problems(ReviewOutcome.READY, Set.of(MissingItem.LOCATION), null)).isEmpty();
		assertThat(ValidReviewDecision.Validator.problems(null)).isEmpty();
	}

	private static List<String> problems(ReviewOutcome outcome, Set<MissingItem> items, Fields fields) {
		return ValidReviewDecision.Validator.problems(new ReviewDecisionRequest(outcome, items, fields, 0L));
	}
}
