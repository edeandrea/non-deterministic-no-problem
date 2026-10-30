package org.parasol.intake.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.parasol.claim.model.ClaimCategory;
import org.parasol.claim.model.ClaimImageKind;
import org.parasol.intake.IntakeFlowTestBase;
import org.parasol.intake.IntakeScenario.Mail;
import org.parasol.intake.MissingItem;
import org.parasol.intake.config.IntakeConfig;
import org.parasol.intake.mailbox.MailFolder;
import org.parasol.intake.model.EmailType;
import org.parasol.testing.StubbedModelsTestProfile;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.serverlessworkflow.impl.WorkflowInstance;
import io.serverlessworkflow.impl.WorkflowStatus;
import io.serverlessworkflow.impl.persistence.PersistenceInstanceReader;

/**
 * New claims, end to end against the Compose GreenMail and a stubbed model.
 */
@QuarkusTest
@TestProfile(StubbedModelsTestProfile.class)
class ClaimIntakeNewClaimTests extends IntakeFlowTestBase {
	@Inject
	IntakeConfig config;

	@Inject
	ClaimIntakeFlow flow;

	@Inject
	PersistenceInstanceReader persistedRuns;

	@Test
	void aCompleteNewClaimWaitsForReviewAndGetsOneReceivedEmail() {
		var marker = marker();
		stubNewClaim(marker, COMPLETE_DETAILS);

		var id = this.scenario.submit(Mail.from(SENDER).name(SENDER_NAME).subject("Car accident").body("I hit a post. " + marker));

		this.scenario.awaitFiled(id);
		var claim = this.scenario.awaitClaimOf(SENDER);
		var run = this.scenario.awaitWaitingRun(claim.id);

		// Persisted as waiting, not only waiting in memory: what the review endpoint (task 10) reads
		assertThat(this.persistedRuns.find(this.flow.definition(), run.id()))
			.get()
			.returns(WorkflowStatus.WAITING, WorkflowInstance::status);

		assertThat(this.scenario.claim(claim.id))
			.returns("Pending Review", c -> c.status)
			.returns(SENDER, c -> c.emailAddress)
			.returns(SENDER_NAME, c -> c.clientName)
			.returns("Car accident", c -> c.subject)
			.returns(LocalDate.of(2026, 10, 7), c -> c.incidentDate)
			.returns(ClaimCategory.SINGLE_VEHICLE, c -> c.category)
			.satisfies(c -> assertThat(c.inceptionDate).isBefore(LocalDate.of(2026, 10, 7)))
			.satisfies(c -> assertThat(c.policyNumber).matches("AC-\\d{9}"));
		assertThat(this.scenario.intake(claim.id))
			.get()
			.returns(run.id(), state -> state.reviewRunId)
			.returns(null, state -> state.decidingRunId)
			.returns(true, state -> state.policyNumberGenerated)
			.satisfies(state -> assertThat(state.intakeConversationId).isNotBlank())
			.satisfies(state -> assertThat(state.requestedItems).isEmpty());
		assertThat(this.scenario.awaitReplies(SENDER, 1))
			.singleElement()
			.satisfies(reply -> assertThat(reply.subject()).startsWith("[%s] Re: Car accident".formatted(claim.claimNumber)))
			.satisfies(reply -> assertThat(reply.body()).contains(claim.claimNumber, "Pending Review"));
		assertThat(this.scenario.inFolder(MailFolder.PROCESSING)).isEmpty();
	}

	@Test
	void theIncidentTimeIsPersistedAsALocalTime() {
		var marker = marker();
		stubNewClaim(marker, COMPLETE_DETAILS.replace("\"incidentTime\":null", "\"incidentTime\":\"14:30\""));

		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).body("I hit a post. " + marker)));

		assertThat(this.scenario.claim(this.scenario.awaitClaimOf(SENDER).id))
			.returns(LocalTime.of(14, 30), c -> c.incidentTime);
	}

	@Test
	void everyMissingItemIsAskedForAndOtherCategoryIsComplete() {
		var marker = marker();
		stubNewClaim(marker, details(null, null, null, null, null, List.of()));

		var id = this.scenario.submit(Mail.from(SENDER).subject("Help").body("Something happened. " + marker));

		this.scenario.awaitFiled(id);
		var claim = this.scenario.claim(this.scenario.awaitClaimOf(SENDER).id);

		assertThat(claim.status).isEqualTo("Pending Information");
		assertThat(this.scenario.intake(claim.id))
			.get()
			.satisfies(state -> assertThat(state.requestedItems).containsExactlyInAnyOrder(MissingItem.values()))
			.returns(null, state -> state.reviewRunId);
		assertThat(this.scenario.awaitReplies(SENDER, 1))
			.singleElement()
			.satisfies(reply -> assertThat(reply.body()).contains("incident description", "incident date", "location", "category"));

		var other = marker();
		stubNewClaim(other, details(true, "2026-10-07", "Home", "OTHER", null, List.of()));
		this.scenario.awaitFiled(this.scenario.submit(Mail.from("other.person@example.com").body("Stuff broke. " + other)));

		assertThat(this.scenario.claim(this.scenario.awaitClaimOf("other.person@example.com").id).status)
			.isEqualTo("Pending Review");
	}

	@ParameterizedTest
	@EnumSource(MissingItem.class)
	void eachMissingItemIsAskedForOnItsOwn(MissingItem item) {
		var marker = marker();
		stubNewClaim(marker, detailsWithout(item));

		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).body("Something happened. " + marker)));

		var claim = this.scenario.awaitClaimOf(SENDER);

		assertThat(this.scenario.claim(claim.id).status).isEqualTo("Pending Information");
		assertThat(this.scenario.intake(claim.id).orElseThrow().requestedItems).containsExactly(item);
		assertThat(this.scenario.awaitReplies(SENDER, 1))
			.singleElement()
			.satisfies(reply -> assertThat(reply.body()).contains("- " + item.label()));
	}

	@Test
	void severalMissingItemsAreListedExactly() {
		var marker = marker();
		stubNewClaim(marker, details(true, null, null, "THEFT", null, List.of()));

		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).body("Stolen. " + marker)));

		assertThat(this.scenario.intake(this.scenario.awaitClaimOf(SENDER).id))
			.get()
			.extracting(state -> state.requestedItems)
			.asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.COLLECTION)
			.containsExactlyInAnyOrder(MissingItem.INCIDENT_DATE, MissingItem.LOCATION);
	}

	@Test
	void photosAreStoredAndSkippedAttachmentsAreMentioned() {
		var marker = marker();
		stubNewClaim(marker, COMPLETE_DETAILS);

		var id = this.scenario.submit(Mail.from(SENDER).subject("With photos").body("Photos. " + marker)
			.attachment("front.jpg", "image/jpeg", JPEG)
			.attachment("report.pdf", "application/pdf", new byte[] { '%', 'P', 'D', 'F' }));

		this.scenario.awaitFiled(id);
		var claim = this.scenario.awaitClaimOf(SENDER);

		assertThat(this.scenario.images(claim.id).stream().map(image -> image.kind + ":" + image.fileName).toList())
			.containsExactly(ClaimImageKind.ORIGINAL + ":front.jpg");
		assertThat(this.scenario.awaitReplies(SENDER, 1))
			.singleElement()
			.satisfies(reply -> assertThat(reply.body()).contains("report.pdf").doesNotContain("send us photos"));
	}

	@Test
	void anImageOverTheSizeLimitIsSkippedAndMentioned() {
		var marker = marker();
		stubNewClaim(marker, COMPLETE_DETAILS);
		var tooBig = new byte[Math.toIntExact(this.config.limits().maxImageSize().asLongValue() + 1)];
		System.arraycopy(JPEG, 0, tooBig, 0, JPEG.length);

		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).body("A huge photo. " + marker).attachment("huge.jpg", "image/jpeg", tooBig)));

		var claim = this.scenario.awaitClaimOf(SENDER);

		assertThat(this.scenario.images(claim.id)).isEmpty();
		assertThat(this.scenario.awaitReplies(SENDER, 1))
			.singleElement()
			.satisfies(reply -> assertThat(reply.body()).contains("huge.jpg"));
	}

	@Test
	void noPhotosGetsTheNote() {
		var marker = marker();
		stubNewClaim(marker, COMPLETE_DETAILS);

		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).body("No photos. " + marker)));

		assertThat(this.scenario.awaitReplies(SENDER, 1))
			.singleElement()
			.satisfies(reply -> assertThat(reply.body()).containsIgnoringCase("photo"));
	}

	@Test
	void anHtmlOnlyEmailIsProcessed() {
		var marker = marker();
		stubNewClaim(marker, COMPLETE_DETAILS);

		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).htmlOnly().body("<p>I hit a post. %s</p>".formatted(marker))));

		assertThat(this.scenario.claim(this.scenario.awaitClaimOf(SENDER).id).status).isEqualTo("Pending Review");
	}

	@Test
	void aNotAClaimEmailGetsItsReplyAndCreatesNothing() {
		var marker = marker();
		classify(marker, EmailType.NOT_A_CLAIM);

		var id = this.scenario.submit(Mail.from(SENDER).subject("Newsletter").body("Unsubscribe me. " + marker));

		this.scenario.awaitFiled(id);

		assertThat(this.scenario.awaitReplies(SENDER, 1)).hasSize(1);
		assertThat(this.scenario.claimCount()).isEqualTo(6);
		assertThat(this.scenario.intakeStateCount()).as("no claim, so no conversation id is persisted").isZero();
	}

	@Test
	void anUnmatchedFollowUpIsNoMatchingClaimAndCreatesNothing() {
		var marker = marker();
		stubFollowUp(marker, COMPLETE_DETAILS);
		classify(marker, EmailType.CLAIM_FOLLOW_UP);

		var id = this.scenario.submit(Mail.from(SENDER).subject("Where is my claim").body("Any news? " + marker));

		this.scenario.awaitFiled(id);

		assertThat(this.scenario.awaitReplies(SENDER, 1))
			.singleElement()
			.satisfies(reply -> assertThat(reply.subject()).doesNotContain("CLM"));
		assertThat(this.scenario.claimCount()).isEqualTo(6);
		assertThat(this.scenario.intakeStateCount()).as("no claim, so no conversation id is persisted").isZero();
	}

	@SuppressWarnings("unused")
	private static Set<MissingItem> none() {
		return Set.of();
	}

	// Complete details with exactly one item left out. Exhaustive on purpose: a new MissingItem won't compile until it's covered
	private static String detailsWithout(MissingItem item) {
		return switch (item) {
			case INCIDENT_DESCRIPTION -> details(null, "2026-10-07", "Elm Street", "SINGLE_VEHICLE", null, List.of());
			case INCIDENT_DATE -> details(true, null, "Elm Street", "SINGLE_VEHICLE", null, List.of());
			case LOCATION -> details(true, "2026-10-07", null, "SINGLE_VEHICLE", null, List.of());
			case CATEGORY -> details(true, "2026-10-07", "Elm Street", null, null, List.of());
		};
	}
}
