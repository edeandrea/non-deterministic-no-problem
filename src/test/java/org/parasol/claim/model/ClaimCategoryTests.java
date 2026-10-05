package org.parasol.claim.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ClaimCategoryTests {
	@ParameterizedTest(name = "[{index}] {arguments}")
	@CsvSource(delimiter = '|', useHeadersInDisplayName = true, textBlock = """
		VALUE                | CATEGORY
		Single vehicle       | SINGLE_VEHICLE
		Multiple vehicle     | MULTIPLE_VEHICLE
		Theft                | THEFT
		Other                | OTHER
		single VEHICLE       | SINGLE_VEHICLE
		SINGLE_VEHICLE       | SINGLE_VEHICLE
		multiple_vehicle     | MULTIPLE_VEHICLE
		'  Other  '          | OTHER
		""")
	void findsByLabelOrName(String value, ClaimCategory expected) {
		assertThat(ClaimCategory.find(value))
			.contains(expected);

		assertThat(ClaimCategory.fromValue(value))
			.isEqualTo(expected);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "Auto", "Single", "Multiple-vehicle", "OTHERS" })
	void unknownValues(String value) {
		assertThat(ClaimCategory.find(value))
			.isEmpty();

		assertThatExceptionOfType(UnknownClaimCategoryException.class)
			.isThrownBy(() -> ClaimCategory.fromValue(value))
			.withMessage("Unknown claim category: %s", value);
	}

	@Test
	void labels() {
		assertThat(ClaimCategory.values())
			.extracting(ClaimCategory::label)
			.containsExactly("Single vehicle", "Multiple vehicle", "Theft", "Other");
	}
}
