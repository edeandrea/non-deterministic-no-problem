package org.parasol.intake.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.parasol.intake.IntakeFlowTestBase;
import org.parasol.intake.IntakeScenario.Mail;
import org.parasol.intake.IntakeTestProfile;
import org.parasol.intake.MissingItem;
import org.parasol.intake.model.EmailType;

import io.quarkiverse.wiremock.devservice.ConnectWireMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Follow-up emails: merging into pending claims, status replies, and emails that match no claim of the sender's.
 */
@QuarkusTest
@TestProfile(IntakeTestProfile.class)
@ConnectWireMock
class ClaimIntakePendingClaimTests extends IntakeFlowTestBase {
	private static final String NO_LOCATION = details("Car hit a post.", "2026-10-07", null, "SINGLE_VEHICLE", null, List.of());
	// A reply has to say which of the requested items it answers, as the agent does from its prompt
	private static final String LOCATION_ANSWERED = details("Car hit a post.", "2026-10-07", "Elm Street", "SINGLE_VEHICLE", null, List.of("LOCATION"));

	@Test
	void aReplyThatCompletesAPendingClaimMovesItToReviewAndKeepsTheConversation() {
		var created = createClaim(SENDER, NO_LOCATION);
		var before = this.scenario.intake(created.claim().id).orElseThrow();

		assertThat(this.scenario.claim(created.claim().id).status).isEqualTo("Pending Information");
		assertThat(before.requestedItems).containsExactly(MissingItem.LOCATION);

		var marker = marker();
		stubFollowUp(marker, LOCATION_ANSWERED);
		var id = this.scenario.submit(Mail.from(SENDER).subject("Re: Car accident").inReplyTo(created.messageId()).body("It was on Elm Street. " + marker));

		this.scenario.awaitFiled(id);
		var run = this.scenario.awaitWaitingRun(created.claim().id);
		var claim = this.scenario.claim(created.claim().id);

		assertThat(claim)
			.returns("Pending Review", c -> c.status)
			.returns("Elm Street", c -> c.location)
			.satisfies(c -> assertThat(c.body).contains("First email.", "It was on Elm Street."));
		assertThat(this.scenario.intake(created.claim().id))
			.get()
			.returns(run.id(), state -> state.reviewRunId)
			.returns(before.intakeConversationId, state -> state.intakeConversationId)
			.satisfies(state -> assertThat(state.requestedItems).isEmpty());
		assertThat(this.scenario.awaitReplies(SENDER, 2))
			.hasSize(2)
			.last()
			.satisfies(reply -> assertThat(reply.body()).contains("Pending Review"));
	}

	@Test
	void aClaimIsCompletedOnlyByTheSecondReply() {
		var created = createClaim(SENDER, details("Car hit a post.", null, null, "SINGLE_VEHICLE", null, List.of()));

		assertThat(this.scenario.intake(created.claim().id).orElseThrow().requestedItems)
			.containsExactlyInAnyOrder(MissingItem.INCIDENT_DATE, MissingItem.LOCATION);

		var first = marker();
		stubFollowUp(first, details("Car hit a post.", "2026-10-07", null, "SINGLE_VEHICLE", null, List.of("INCIDENT_DATE")));
		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).inReplyTo(created.messageId()).body("It was on the 7th. " + first)));

		assertThat(this.scenario.claim(created.claim().id).status).isEqualTo("Pending Information");
		assertThat(this.scenario.intake(created.claim().id).orElseThrow().requestedItems).containsExactly(MissingItem.LOCATION);

		var second = marker();
		stubFollowUp(second, details("Car hit a post.", "2026-10-07", "Elm Street", "SINGLE_VEHICLE", null, List.of("LOCATION")));
		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).inReplyTo(created.messageId()).body("On Elm Street. " + second)));

		var run = this.scenario.awaitWaitingRun(created.claim().id);

		assertThat(this.scenario.claim(created.claim().id))
			.returns("Pending Review", c -> c.status)
			.returns(LocalDate.of(2026, 10, 7), c -> c.incidentDate)
			.returns("Elm Street", c -> c.location);
		assertThat(this.scenario.intake(created.claim().id).orElseThrow().reviewRunId).isEqualTo(run.id());
		assertThat(this.scenario.awaitReplies(SENDER, 3))
			.hasSize(3)
			.last()
			.satisfies(reply -> assertThat(reply.body()).contains("Pending Review"));
	}

	@Test
	void aReplyThatStillLacksSomethingGetsTheStillMissingEmail() {
		var created = createClaim(SENDER, details(null, "2026-10-07", null, "SINGLE_VEHICLE", null, List.of()));

		var marker = marker();
		stubFollowUp(marker, details("Car hit a post.", "2026-10-07", null, "SINGLE_VEHICLE", null, List.of("INCIDENT_DESCRIPTION")));
		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).inReplyTo(created.messageId()).body("It hit a post. " + marker)));

		assertThat(this.scenario.claim(created.claim().id).status).isEqualTo("Pending Information");
		assertThat(this.scenario.intake(created.claim().id).orElseThrow().requestedItems).containsExactly(MissingItem.LOCATION);
		assertThat(this.scenario.awaitReplies(SENDER, 2))
			.last()
			.satisfies(reply -> assertThat(reply.body()).contains("location").doesNotContain("incident description"));
	}

	@Test
	void theMatchedClaimWinsOverTheClassifier() {
		var created = createClaim(SENDER, NO_LOCATION);

		var marker = marker();
		classify(marker, EmailType.NOT_A_CLAIM);
		extract(marker, LOCATION_ANSWERED);
		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).inReplyTo(created.messageId()).body("Elm Street. " + marker)));

		assertThat(this.scenario.claim(created.claim().id))
			.returns("Pending Review", c -> c.status)
			.returns("Elm Street", c -> c.location);
	}

	@Test
	void aClaimNumberInTheSubjectMatchesWithoutReplyHeaders() {
		var created = createClaim(SENDER, NO_LOCATION);

		var marker = marker();
		stubFollowUp(marker, LOCATION_ANSWERED);
		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).subject("About " + created.claim().claimNumber).body("Elm Street. " + marker)));

		assertThat(this.scenario.claim(created.claim().id).status).isEqualTo("Pending Review");
		assertThat(this.scenario.claimCount(SENDER)).isEqualTo(1);
	}

	@Test
	void aFollowUpOnAClaimBeyondReviewGetsAStatusReplyAndChangesNothing() {
		var seeded = this.scenario.claim(1L);
		var marker = marker();
		classify(marker, EmailType.CLAIM_FOLLOW_UP);

		var id = this.scenario.submit(Mail.from(seeded.emailAddress).subject("Status of " + seeded.claimNumber).body("Any news? " + marker));

		this.scenario.awaitFiled(id);

		assertThat(this.scenario.awaitReplies(seeded.emailAddress, 1))
			.singleElement()
			.satisfies(reply -> assertThat(reply.body()).contains(STATUS_ANSWER))
			.satisfies(reply -> assertThat(reply.subject()).startsWith("[%s] Re:".formatted(seeded.claimNumber)));
		assertThat(this.scenario.claim(1L))
			.returns(seeded.status, c -> c.status)
			.returns(seeded.body, c -> c.body);
		assertThat(this.scenario.intake(1L)).isEmpty();
	}

	@Test
	void aFollowUpFromAnotherAddressGetsNoMatchingClaimAndDisclosesNothing() {
		var seeded = this.scenario.claim(1L);
		var marker = marker();
		classify(marker, EmailType.CLAIM_FOLLOW_UP);

		this.scenario.awaitFiled(this.scenario.submit(Mail.from("stranger@example.com").subject("About " + seeded.claimNumber).body("Tell me. " + marker)));

		assertThat(this.scenario.awaitReplies("stranger@example.com", 1))
			.singleElement()
			.satisfies(reply -> assertThat(reply.subject()).as("no claim-number prefix: only the customer's own subject is echoed").startsWith("Re: About"))
			.satisfies(reply -> assertThat(reply.body()).doesNotContain(seeded.claimNumber, seeded.clientName));
		assertThat(this.scenario.claim(1L).status).isEqualTo(seeded.status);
	}
}
