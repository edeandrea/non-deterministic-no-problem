package org.parasol.intake.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.parasol.claim.model.ClaimCategory;
import org.parasol.intake.IntakeTestProfile;
import org.parasol.intake.MissingItem;
import org.parasol.intake.model.IntakeOutcome.NewClaim;
import org.parasol.intake.model.IntakeOutcome.NoMatchingClaim;
import org.parasol.intake.model.IntakeOutcome.NotAClaim;
import org.parasol.intake.model.IntakeOutcome.PendingClaimUpdate;
import org.parasol.intake.model.IntakeOutcome.StatusReply;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Flow persists step data and the agentic scope with the Quarkus {@code ObjectMapper}, so the triage values must survive
 * a round trip through it.
 */
@QuarkusTest
@TestProfile(IntakeTestProfile.class)
class IntakeOutcomeRoundTripTests {
	private static final ClaimExtraction EXTRACTION = new ClaimExtraction("summary", "sentiment", new IncidentDetails(
		"car hit a tree", "2026-10-08", "14:30", "Elm Street", ClaimCategory.SINGLE_VEHICLE, "AC-1234567", Set.of(MissingItem.LOCATION)));

	@Inject
	ObjectMapper objectMapper;

	@Test
	void everyIntakeOutcomeRoundTripsAsAnIntakeOutcome() {
		var outcomes = List.of(
			new NewClaim(EXTRACTION),
			new PendingClaimUpdate(EXTRACTION),
			new StatusReply("Your claim is In Process."),
			new NotAClaim(),
			new NoMatchingClaim());

		assertThat(outcomes)
			.allSatisfy(outcome -> assertThat(roundTrip(outcome, IntakeOutcome.class)).isEqualTo(outcome));
	}

	@Test
	void aMatchedClaimAndNoneRoundTripAsTwoStrings() throws JsonProcessingException {
		var matched = MatchedClaim.of("CLM01000000", "Pending Review");

		assertThat(roundTrip(matched, MatchedClaim.class))
			.isEqualTo(matched);
		assertThat(roundTrip(MatchedClaim.none(), MatchedClaim.class))
			.isEqualTo(MatchedClaim.none());
		assertThat(this.objectMapper.readTree(this.objectMapper.writeValueAsString(matched)).properties())
			.extracting(Map.Entry::getKey)
			.containsExactlyInAnyOrder("claimNumber", "status");
	}

	@Test
	void aClaimResolutionRoundTrips() {
		var resolution = new ClaimResolution(ClaimResolution.Kind.EXISTING, "CLM01000000");

		assertThat(roundTrip(resolution, ClaimResolution.class))
			.isEqualTo(resolution);
	}

	private <T> T roundTrip(Object value, Class<T> type) {
		try {
			return this.objectMapper.readValue(this.objectMapper.writeValueAsString(value), type);
		}
		catch (JsonProcessingException e) {
			throw new AssertionError("Couldn't round-trip " + value, e);
		}
	}
}
