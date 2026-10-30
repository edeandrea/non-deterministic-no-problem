package org.parasol.intake.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.parasol.claim.model.ClaimCategory;
import org.parasol.intake.MissingItem;
import org.parasol.intake.model.ClaimExtraction;
import org.parasol.intake.model.IncidentDetails;
import org.parasol.intake.model.IntakeOutcome;
import org.parasol.intake.review.ReviewDecided;
import org.parasol.intake.review.ReviewDecision;
import org.parasol.intake.review.ReviewOutcome;
import org.parasol.intake.review.ReviewedFields;
import org.parasol.intake.workflow.IntakeState.Disposition;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;

/**
 * What Flow persists after every step has to survive the Quarkus {@code ObjectMapper}, which is what Flow serializes with.
 */
@QuarkusTest
class IntakeJsonRoundTripTests {
	@Inject
	ObjectMapper objectMapper;

	@Test
	void anIntakeRunRoundTrips() throws Exception {
		var forEmail = IntakeRun.forEmail("<id@example.com>", "conversation", "jane@example.com", 7L, true, Instant.parse("2026-10-29T10:15:30Z"));

		assertRoundTrips(forEmail);
		assertRoundTrips(IntakeRun.forEmail("<id@example.com>", "conversation", "jane@example.com", null, false, Instant.parse("2026-10-29T10:15:30Z")));
		assertRoundTrips(IntakeRun.resumingReview(forEmail, "conversation", 7L));
	}

	@Test
	void anIntakeRunWithoutAReceivedTimeRoundTrips() throws Exception {
		// A run persisted before the received time was recorded has none
		var legacy = new IntakeRun("<id@example.com>", "conversation", "jane@example.com", 7L, true, false, null);

		assertRoundTrips(legacy);
		assertThat(this.objectMapper.readValue("""
			{"messageId":"<id@example.com>","conversationId":"conversation","sender":"jane@example.com","claimId":7,"senderMatches":true,"resumesReview":false}""",
			IntakeRun.class))
			.isEqualTo(legacy);
		assertRoundTrips(IntakeRun.resumingReview(legacy, "conversation", 7L));
	}

	@Test
	void theReceivedTimeSurvivesTheRoundTripOfARunAndItsState() throws Exception {
		var run = IntakeRun.forEmail("<id@example.com>", "conversation", "jane@example.com", 7L, true, Instant.parse("2026-10-29T10:15:30Z"));

		assertThat(this.objectMapper.readValue(this.objectMapper.writeValueAsString(run), IntakeRun.class).receivedAt()).isEqualTo("2026-10-29T10:15:30Z");
		assertRoundTrips(IntakeState.start("<id@example.com>", "jane@example.com", null));
		assertThat(this.objectMapper.readValue(this.objectMapper.writeValueAsString(IntakeState.start("<id@example.com>", "jane@example.com", run.receivedAt())),
			IntakeState.class).receivedAt()).isEqualTo("2026-10-29T10:15:30Z");
	}

	@Test
	void aFullIntakeStateRoundTrips() throws Exception {
		var details = new IncidentDetails("Hit a post", "2026-10-07", "14:30", "Elm Street", ClaimCategory.SINGLE_VEHICLE, "AC-123456789", Set.of(MissingItem.LOCATION));
		var state = new IntakeState("<id@example.com>", "jane@example.com", 7L, Disposition.WHICH_CLAIM, true, List.of("CLM01000000", "CLM01001009"),
			new IntakeOutcome.NewClaim(new ClaimExtraction("A summary", "Calm", details)), "AC-123456789", true,
			Set.of(MissingItem.LOCATION, MissingItem.CATEGORY), true, false, "2026-10-29T10:15:30Z");

		assertRoundTrips(state);
	}

	@Test
	void aMinimalIntakeStateRoundTrips() throws Exception {
		assertRoundTrips(IntakeState.start("<id@example.com>", "jane@example.com", "2026-10-29T10:15:30Z"));
	}

	@Test
	void everyOutcomeVariantRoundTripsInsideTheState() throws Exception {
		for (var outcome : List.of(new IntakeOutcome.StatusReply("Answer"), new IntakeOutcome.NotAClaim(), new IntakeOutcome.NoMatchingClaim())) {
			assertRoundTrips(IntakeState.start("<id@example.com>", "jane@example.com", "2026-10-29T10:15:30Z").withOutcome(outcome));
		}
	}

	@Test
	void aReviewDecidedRoundTrips() throws Exception {
		assertRoundTrips(new ReviewDecided(7L, "run-1", new ReviewDecision(ReviewOutcome.NEEDS_INFORMATION, Set.of(MissingItem.LOCATION, MissingItem.INCIDENT_DATE),
			new ReviewedFields("Car hit a post.", null, null, null, ClaimCategory.SINGLE_VEHICLE))));
		assertRoundTrips(new ReviewDecided(7L, "run-1", new ReviewDecision(ReviewOutcome.READY, Set.of(),
			new ReviewedFields("Car hit a post.", LocalDate.of(2026, 10, 7), LocalTime.of(15, 30), "Elm Street", ClaimCategory.MULTIPLE_VEHICLE))));
		assertRoundTrips(new ReviewDecided(7L, "run-1", new ReviewDecision(ReviewOutcome.READY, Set.of(), ReviewedFields.none())));
	}

	private void assertRoundTrips(Object value) throws Exception {
		var json = this.objectMapper.writeValueAsString(value);

		assertThat(this.objectMapper.readValue(json, value.getClass())).isEqualTo(value);
	}
}
