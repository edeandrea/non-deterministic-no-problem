package org.parasol.intake.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.parasol.intake.IntakeFlowTestBase;
import org.parasol.intake.IntakeScenario.Mail;
import org.parasol.intake.mailbox.EmailReceived;
import org.parasol.intake.mailbox.MailFolder;
import org.parasol.intake.model.EmailType;
import org.parasol.testing.StubbedModelsTestProfile;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * The starter: it doesn't wait for runs, keeps one sender's emails in order, and starts no run for auto-replies, self-sent
 * mail or a duplicate {@code Message-ID}.
 */
@QuarkusTest
@TestProfile(StubbedModelsTestProfile.class)
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
		this.stubs.answerWhenAll(List.of(DETAILS, firstMarker), details("Car hit a post.", "2026-10-07", null, "SINGLE_VEHICLE", null, List.of()), 1500);

		var secondMarker = marker();
		stubFollowUp(secondMarker, details("Car hit a post.", "2026-10-07", "Elm Street", "SINGLE_VEHICLE", null, List.of("LOCATION")));

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

		Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> this.scenario.inFolder(MailFolder.PROCESSING).isEmpty());
		assertThat(this.scenario.awaitReplies(SENDER, 1)).hasSize(1);
	}

	@Test
	void anAutoReplyAndASelfSentEmailStartNoRunAndGetNoReply() {
		this.scenario.submit(Mail.from(SENDER).header("Auto-Submitted", "auto-replied").body("Out of office"));
		this.scenario.submit(Mail.from("claims@parasol.com").body("From ourselves"));

		Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> this.scenario.inFolder(MailFolder.PROCESSED).size() == 2);

		assertThat(this.scenario.replies(SENDER)).isEmpty();
		assertThat(this.scenario.replies("claims@parasol.com")).isEmpty();
		assertThat(this.scenario.instanceRows()).isZero();
	}
}
