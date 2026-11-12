package org.parasol.intake.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.parasol.claim.model.ClaimCategory;

class IncidentDetailsTests {
	@ParameterizedTest
	@ValueSource(strings = {"null", "NULL", "Null", "  null  ", "", "   "})
	void aBlankOrNullStringIsAbsentForEveryStringField(String value) {
		var details = new IncidentDetails(true, value, value, value, ClaimCategory.OTHER, value, Set.of());

		assertThat(details)
			.returns(null, IncidentDetails::incidentDate)
			.returns(null, IncidentDetails::incidentTime)
			.returns(null, IncidentDetails::location)
			.returns(null, IncidentDetails::policyNumber);
	}

	@Test
	void realValuesAreKeptStripped() {
		var details = new IncidentDetails(true, " 2026-10-07", "14:30 ", " Nullarbor Road ", ClaimCategory.THEFT, " AC-123456789 ", Set.of());

		assertThat(details)
			.returns("2026-10-07", IncidentDetails::incidentDate)
			.returns("14:30", IncidentDetails::incidentTime)
			.returns("Nullarbor Road", IncidentDetails::location)
			.returns("AC-123456789", IncidentDetails::policyNumber);
	}
}
