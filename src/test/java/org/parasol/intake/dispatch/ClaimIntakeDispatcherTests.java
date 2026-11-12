package org.parasol.intake.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.parasol.intake.IntakeFlowTestBase;
import org.parasol.intake.IntakeScenario;
import org.parasol.intake.IntakeScenario.Mail;
import org.parasol.intake.mailbox.EmailReceived;
import org.parasol.intake.mailbox.MailFolder;
import org.parasol.intake.model.EmailType;
import org.parasol.intake.review.ReviewDecided;
import org.parasol.intake.review.ReviewDecision;
import org.parasol.intake.review.ReviewOutcome;
import org.parasol.intake.review.ReviewedFields;

import io.quarkus.test.junit.QuarkusTest;

/**
 * The starter: it doesn't wait for runs, keeps one sender's emails in order, and starts no run for auto-replies, self-sent
 * mail or a duplicate {@code Message-ID}.
 */
@QuarkusTest
class ClaimIntakeDispatcherTests extends IntakeFlowTestBase {
	@Inject
	ClaimIntakeDispatcher starter;

	@Inject
	Event<EmailReceived> emailReceived;

	// The observer runs on the real bean, so the effect is asserted: @InjectMock disables observers, and an @InjectSpy never sees the call
	@Test
	void anEmailReceivedEventStartsARunForItsEmail() {
		var marker = marker();
		classify(marker, EmailType.NOT_A_CLAIM);
		var email = this.scenario.deliverToProcessing(Mail.from(SENDER).body("Newsletter please. " + marker));

		this.emailReceived.fire(new EmailReceived(email));

		this.scenario.awaitFiled(email.messageId().orElseThrow());
		assertThat(this.scenario.awaitReplies(SENDER, 1)).hasSize(1);
	}

	@Test
	void submitReturnsBeforeTheRunsFirstStepFinishes() {
		var marker = marker();
		this.stubs.answerWhenAll(List.of(CLASSIFY, marker), EmailType.NOT_A_CLAIM.name(), 3000);
		var email = this.scenario.deliverToProcessing(Mail.from(SENDER).body("Newsletter please. " + marker));

		var startedAt = System.nanoTime();
		this.starter.submit(email);
		var took = Duration.ofNanos(System.nanoTime() - startedAt);

		assertThat(took).isLessThan(Duration.ofSeconds(2));
		this.scenario.awaitFiled(email.messageId().orElseThrow());
	}

	@Test
	void twoBackToBackEmailsFromOneSenderRunInOrderAndAreBothMerged() {
		var firstId = "<%s@example.com>".formatted(UUID.randomUUID());
		var firstMarker = marker();
		classify(firstMarker, EmailType.NEW_CLAIM);
		// Slow enough that, run in parallel, the second email would be matched before the first created its claim
		this.stubs.answerWhenAll(List.of(DETAILS, firstMarker), details(true, "2026-10-07", null, "SINGLE_VEHICLE", null, List.of()), 1500);

		var secondMarker = marker();
		stubFollowUp(secondMarker, details(true, "2026-10-07", "Elm Street", "SINGLE_VEHICLE", null, List.of("LOCATION")));

		this.scenario.submit(Mail.from(SENDER).messageId(firstId).subject("Accident").body("I hit a post. " + firstMarker));
		var secondId = this.scenario.submit(Mail.from(SENDER).inReplyTo(firstId).subject("Re: Accident").body("On Elm Street. " + secondMarker));

		this.scenario.awaitFiled(firstId);
		this.scenario.awaitFiled(secondId);

		assertThat(this.scenario.claimCount(SENDER)).isEqualTo(1);
		assertThat(this.scenario.claim(this.scenario.awaitClaimOf(SENDER).id))
			.returns("Pending Review", c -> c.status)
			.satisfies(c -> assertThat(c.body).contains("I hit a post.", "On Elm Street."));
		assertThat(this.scenario.awaitReplies(SENDER, 2)).hasSize(2);
	}

	@Test
	void aRedeliveredNotAClaimEmailGetsOneReply() {
		var marker = marker();
		classify(marker, EmailType.NOT_A_CLAIM);
		var messageId = "<%s@example.com>".formatted(java.util.UUID.randomUUID());

		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).messageId(messageId).body("Newsletter please. " + marker)));
		this.scenario.submit(Mail.from(SENDER).messageId(messageId).body("Newsletter please. " + marker));

		await()
			.atMost(Duration.ofSeconds(30))
			.until(() -> this.scenario.inFolder(MailFolder.PROCESSING).isEmpty());
		assertThat(this.scenario.awaitReplies(SENDER, 1)).hasSize(1);
	}

	@Test
	void anAutoReplyAndASelfSentEmailStartNoRunAndGetNoReply() {
		this.scenario.submit(Mail.from(SENDER).header("Auto-Submitted", "auto-replied").body("Out of office"));
		this.scenario.submit(Mail.from("claims@parasol.com").body("From ourselves"));

		await()
			.atMost(Duration.ofSeconds(30))
			.until(() -> this.scenario.inFolder(MailFolder.PROCESSED).size() == 2);

		assertThat(this.scenario.replies(SENDER)).isEmpty();
		assertThat(this.scenario.replies("claims@parasol.com")).isEmpty();
		assertThat(this.scenario.instanceRows()).isZero();
	}

	@Test
	void anEmailHandedBackIsForgottenOnceItsLastRunEnds() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		var run = this.scenario.awaitWaitingRun(created.claim().id);
		var runId = this.scenario.claimReview(created.claim().id);
		var marker = marker();
		stubFollowUp(marker, COMPLETE_DETAILS);

		var id = this.scenario.submit(Mail.from(SENDER).inReplyTo(created.messageId()).body("One more thing. " + marker));
		// Its first run found the claim being decided and handed it back
		await()
			.atMost(IntakeScenario.WAIT)
			.until(() -> this.starter.requeuesOf(id) > 0);
		this.scenario.publishUntilWoken(run, new ReviewDecided(created.claim().id, runId, new ReviewDecision(ReviewOutcome.READY, Set.of(), ReviewedFields.none())));

		this.scenario.awaitFiled(id);
		await()
			.atMost(IntakeScenario.WAIT)
			.until(() -> this.starter.requeuesOf(id) == 0);
	}
}