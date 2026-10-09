package org.parasol.intake.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.parasol.claim.model.ClaimCategory;
import org.parasol.intake.IntakeTestProfile;
import org.parasol.intake.MissingItem;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Flow persists step data and the agentic scope with the Quarkus {@code ObjectMapper}, so every extraction value must
 * survive a round trip through it.
 */
@QuarkusTest
@TestProfile(IntakeTestProfile.class)
class ClaimExtractionValueRoundTripTests {
	@Inject
	ObjectMapper objectMapper;

	@Test
	void incidentDetailsAndClaimExtractionRoundTrip() throws Exception {
		var details = new IncidentDetails(
			"car hit a tree",
			"2026-10-08",
			"14:30",
			"Elm Street",
			ClaimCategory.MULTIPLE_VEHICLE,
			"AC-1234567",
			Set.of(MissingItem.INCIDENT_DESCRIPTION, MissingItem.INCIDENT_DATE)
		);
		var extraction = new ClaimExtraction("summary", "sentiment", details);

		assertThat(this.objectMapper.readValue(this.objectMapper.writeValueAsString(details), IncidentDetails.class))
			.isEqualTo(details);
		assertThat(this.objectMapper.readValue(this.objectMapper.writeValueAsString(extraction), ClaimExtraction.class))
			.isEqualTo(extraction);
	}

	@Test
	void allNullDetailsRoundTripWithAnEmptyAnsweredSet() throws Exception {
		var empty = new IncidentDetails(null, null, null, null, null, null, null);

		assertThat(this.objectMapper.readValue(this.objectMapper.writeValueAsString(empty), IncidentDetails.class))
			.isEqualTo(empty)
			.extracting(IncidentDetails::answeredItems)
			.isEqualTo(Set.of());
	}
}
