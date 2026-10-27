package org.parasol.intake.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;
import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimCategory;
import org.parasol.intake.MissingItem;

class ReviewedFieldsTests {
	@Test
	void everyGivenValueIsSet() {
		var claim = claim();

		new ReviewedFields("Car hit a tree.", LocalDate.of(2026, 10, 8), LocalTime.of(9, 15), "Oak Avenue", ClaimCategory.MULTIPLE_VEHICLE).applyTo(claim);

		assertThat(claim)
			.extracting(c -> c.summary, c -> c.incidentDate, c -> c.incidentTime, c -> c.location, c -> c.category)
			.containsExactly("Car hit a tree.", LocalDate.of(2026, 10, 8), LocalTime.of(9, 15), "Oak Avenue", ClaimCategory.MULTIPLE_VEHICLE);
	}

	@Test
	void noValuesLeaveTheClaimAsItIs() {
		var claim = claim();

		ReviewedFields.none().applyTo(claim);

		assertThat(claim)
			.extracting(c -> c.summary, c -> c.incidentDate, c -> c.incidentTime, c -> c.location, c -> c.category)
			.containsExactly("Car hit a post.", LocalDate.of(2026, 10, 7), LocalTime.of(15, 30), "Elm Street", ClaimCategory.SINGLE_VEHICLE);
	}

	@Test
	void aDateWithoutATimeClearsTheTime() {
		var claim = claim();

		new ReviewedFields(null, LocalDate.of(2026, 10, 8), null, null, null).applyTo(claim);

		assertThat(claim)
			.extracting(c -> c.incidentDate, c -> c.incidentTime)
			.containsExactly(LocalDate.of(2026, 10, 8), null);
	}

	@Test
	void aTimeWithoutADateIsIgnored() {
		var claim = claim();

		new ReviewedFields(null, null, LocalTime.NOON, null, null).applyTo(claim);

		assertThat(claim.incidentTime).isEqualTo(LocalTime.of(15, 30));
	}

	@Test
	void eachItemHasItsOwnField() {
		var description = new ReviewedFields("Car hit a post.", null, null, null, null);
		var time = new ReviewedFields(null, null, LocalTime.NOON, null, null);

		assertThat(description.hasValueFor(MissingItem.INCIDENT_DESCRIPTION)).isTrue();
		assertThat(description.hasValueFor(MissingItem.INCIDENT_DATE)).isFalse();
		assertThat(time.hasValueFor(MissingItem.INCIDENT_DATE)).isFalse();
		assertThat(new ReviewedFields(null, null, null, "Elm Street", ClaimCategory.OTHER))
			.satisfies(fields -> assertThat(fields.hasValueFor(MissingItem.LOCATION)).isTrue())
			.satisfies(fields -> assertThat(fields.hasValueFor(MissingItem.CATEGORY)).isTrue());
	}

	private static Claim claim() {
		var claim = new Claim();
		claim.summary = "Car hit a post.";
		claim.incidentDate = LocalDate.of(2026, 10, 7);
		claim.incidentTime = LocalTime.of(15, 30);
		claim.location = "Elm Street";
		claim.category = ClaimCategory.SINGLE_VEHICLE;

		return claim;
	}
}
