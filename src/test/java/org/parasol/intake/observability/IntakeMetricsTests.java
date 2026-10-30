package org.parasol.intake.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimCategory;
import org.parasol.claim.model.IntakeClaimStatus;
import org.parasol.intake.IntakeFlowTestBase;
import org.parasol.intake.IntakeScenario;
import org.parasol.intake.IntakeScenario.Mail;
import org.parasol.intake.MissingItem;
import org.parasol.intake.failure.IntakeFailureHandler;
import org.parasol.intake.mailbox.ClaimsMailbox;
import org.parasol.intake.mailbox.MailFolder;
import org.parasol.intake.mailbox.SkippedAttachment;
import org.parasol.intake.model.EmailType;
import org.parasol.intake.observability.IntakeActivity.ClaimCompleted;
import org.parasol.intake.observability.IntakeActivity.InboxDepthMeasured;
import org.parasol.intake.observability.IntakeActivity.NewClaimExtracted;
import org.parasol.intake.observability.IntakeActivity.ClaimStatusChanged;
import org.parasol.intake.observability.IntakeActivity.ReviewDecisionApplied;
import org.parasol.intake.reply.IntakeReplySender;
import org.parasol.intake.reply.IntakeTemplates;
import org.parasol.intake.review.ReviewDecided;
import org.parasol.intake.review.ReviewDecision;
import org.parasol.intake.review.ReviewOutcome;
import org.parasol.intake.review.ReviewedFields;
import org.parasol.intake.workflow.outcome.ApplyOutcomeStep;
import org.parasol.intake.workflow.review.ReviewDecisionSteps;
import org.parasol.testing.StubbedModelsTestProfile;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.mailer.MailTemplate.MailTemplateInstance;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;

/**
 * What the intake's meters say about the emails, claims, replies and reviews it handles, and that none of their tags carries
 * a person, an email's id or a claim.
 */
@QuarkusTest
@TestProfile(StubbedModelsTestProfile.class)
class IntakeMetricsTests extends IntakeFlowTestBase {
	private static final String NO_LOCATION = details(true, "2026-10-07", null, "SINGLE_VEHICLE", null, List.of());

	@Inject
	SimpleMeterRegistry registry;

	@InjectSpy
	ReviewDecisionSteps decisionSteps;

	@InjectSpy
	IntakeReplySender sender;

	@InjectSpy
	ApplyOutcomeStep applying;

	@Inject
	ClaimsMailbox mailbox;

	@Inject
	IntakeActivities activities;

	@InjectSpy
	IntakeFailureHandler failureHandler;

	@Test
	void aCompleteClaimIsCountedAndItsDecisionTimed() {
		var emails = count("claim.intake.emails", "outcome", "new_claim");
		var pendingReview = count("claim.intake.claim.status.transitions", "status", "Pending Review");
		var decisions = count("claim.intake.review.decisions", "decision", "ready");
		var inProcess = count("claim.intake.claim.status.transitions", "status", "In Process");
		var replies = count("claim.intake.replies", "template", "claimInProcess");
		var waits = this.registry.timer("claim.intake.review.wait").count();
		var waitedBefore = this.registry.timer("claim.intake.review.wait").totalTime(TimeUnit.NANOSECONDS);

		var claim = createClaim(SENDER, COMPLETE_DETAILS).claim();
		this.scenario.awaitWaitingRun(claim.id);

		assertThat(count("claim.intake.emails", "outcome", "new_claim")).isEqualTo(emails + 1);
		assertThat(count("claim.intake.claim.status.transitions", "status", "Pending Review")).isEqualTo(pendingReview + 1);
		assertThat(pendingReviewsGauge()).isEqualTo(Claim.count("status", "Pending Review"));

		var openedAt = this.scenario.intake(claim.id).orElseThrow().reviewOpenedAt;
		var decidingAt = Instant.now();
		this.scenario.decide(claim.id, ReviewOutcome.READY, Set.of());
		this.scenario.awaitReplies(SENDER, 1);
		Awaitility.await().atMost(IntakeScenario.WAIT).until(() -> count("claim.intake.replies", "template", "claimInProcess") == replies + 1);

		assertThat(count("claim.intake.review.decisions", "decision", "ready")).isEqualTo(decisions + 1);
		assertThat(count("claim.intake.claim.status.transitions", "status", "In Process")).isEqualTo(inProcess + 1);
		assertThat(this.registry.timer("claim.intake.review.wait").count()).isEqualTo(waits + 1);
		assertReviewWaitDuration(waitedBefore, openedAt, decidingAt);
		assertThat(pendingReviewsGauge()).isZero();
	}

	@Test
	void aNotAClaimEmailHasOneTerminalOutcome() {
		var before = counted("claim.intake.emails", "outcome", "not_a_claim");
		var marker = marker();
		classify(marker, EmailType.NOT_A_CLAIM);
		var id = this.scenario.submit(Mail.from(SENDER).subject("Newsletter").body("Unsubscribe me. " + marker));
		this.scenario.awaitFiled(id);
		awaitCounted(before + 1, "claim.intake.emails", "outcome", "not_a_claim");
		assertThat(this.scenario.claimCount(SENDER)).isZero();
	}

	@Test
	void aNeedsInformationDecisionIsCountedAndTimed() {
		var claim = createClaim(SENDER, COMPLETE_DETAILS).claim();
		this.scenario.awaitWaitingRun(claim.id);
		var decisions = counted("claim.intake.review.decisions", "decision", "needs_information");
		var transitions = counted("claim.intake.claim.status.transitions", "status", "Pending Information");
		var replies = counted("claim.intake.replies", "template", "missingInformation");
		var timer = this.registry.timer("claim.intake.review.wait");
		var samples = timer.count();
		var total = timer.totalTime(TimeUnit.NANOSECONDS);
		var openedAt = this.scenario.intake(claim.id).orElseThrow().reviewOpenedAt;
		var decidingAt = Instant.now();

		this.scenario.decide(claim.id, ReviewOutcome.NEEDS_INFORMATION, Set.of(MissingItem.LOCATION));

		awaitCounted(replies + 1, "claim.intake.replies", "template", "missingInformation");
		assertThat(counted("claim.intake.review.decisions", "decision", "needs_information")).isEqualTo(decisions + 1);
		assertThat(counted("claim.intake.claim.status.transitions", "status", "Pending Information")).isEqualTo(transitions + 1);
		assertThat(timer.count()).isEqualTo(samples + 1);
		assertReviewWaitDuration(total, openedAt, decidingAt);
		assertThat(this.scenario.claim(claim.id).status).isEqualTo("Pending Information");
	}

	@Test
	void rolledBackStatusAndDecisionEventsDoNotUpdateMetersButCommittedEventsDo() {
		var statuses = counted("claim.intake.claim.status.transitions", "status", "In Process");
		var decisions = counted("claim.intake.review.decisions", "decision", "ready");
		var timer = this.registry.timer("claim.intake.review.wait");
		var samples = timer.count();
		var total = timer.totalTime(TimeUnit.NANOSECONDS);
		var waited = Duration.ofSeconds(3);

		// Use the real CDI publisher and transaction synchronization, not the direct-event unit-test helper.
		// Committing a rollback-only transaction reports the intentional rollback to the caller.
		assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
			this.activities.publish(new ClaimStatusChanged(IntakeClaimStatus.IN_PROCESS));
			this.activities.publish(new ReviewDecisionApplied(ReviewOutcome.READY, ReviewAgreement.AGREED, Optional.of(waited)));
			QuarkusTransaction.setRollbackOnly();
		}))
			.isInstanceOf(io.quarkus.narayana.jta.QuarkusTransactionException.class)
			.hasCauseInstanceOf(jakarta.transaction.RollbackException.class);
		assertThat(counted("claim.intake.claim.status.transitions", "status", "In Process")).isEqualTo(statuses);
		assertThat(counted("claim.intake.review.decisions", "decision", "ready")).isEqualTo(decisions);
		assertThat(timer.count()).isEqualTo(samples);
		assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isEqualTo(total);

		QuarkusTransaction.requiringNew().run(() -> {
			this.activities.publish(new ClaimStatusChanged(IntakeClaimStatus.IN_PROCESS));
			this.activities.publish(new ReviewDecisionApplied(ReviewOutcome.READY, ReviewAgreement.AGREED, Optional.of(waited)));
		});
		assertThat(counted("claim.intake.claim.status.transitions", "status", "In Process")).isEqualTo(statuses + 1);
		assertThat(counted("claim.intake.review.decisions", "decision", "ready")).isEqualTo(decisions + 1);
		assertThat(timer.count()).isEqualTo(samples + 1);
		assertThat(timer.totalTime(TimeUnit.NANOSECONDS) - total).isEqualTo((double) waited.toNanos());
	}

	private void assertReviewWaitDuration(double totalBefore, Instant openedAt, Instant decidingAt) {
		var elapsed = this.registry.timer("claim.intake.review.wait").totalTime(TimeUnit.NANOSECONDS) - totalBefore;
		// PostgreSQL rounds timestamps to microseconds; allow 1 ms at each interval boundary, not a sleep.
		var tolerance = Duration.ofMillis(1);
		assertThat(elapsed)
			.isPositive()
			.isBetween((double) Duration.between(openedAt, decidingAt).minus(tolerance).toNanos(),
				(double) Duration.between(openedAt, Instant.now()).plus(tolerance).toNanos());
	}

	@Test
	void anIncompleteClaimIsAskedForWhatIsMissing() {
		var pendingInformation = count("claim.intake.claim.status.transitions", "status", "Pending Information");
		var replies = count("claim.intake.replies", "template", "missingInformation");

		createClaim(SENDER, NO_LOCATION);
		this.scenario.awaitReplies(SENDER, 1);
		Awaitility.await().atMost(IntakeScenario.WAIT).until(() -> count("claim.intake.replies", "template", "missingInformation") == replies + 1);

		assertThat(count("claim.intake.claim.status.transitions", "status", "Pending Information")).isEqualTo(pendingInformation + 1);
	}

	@Test
	void anAutoReplyAndADuplicateAreCountedApart() {
		var skipped = count("claim.intake.emails", "outcome", "skipped_auto_reply");
		var duplicates = count("claim.intake.emails", "outcome", "duplicate");
		var marker = marker();
		stubNewClaim(marker, NO_LOCATION);

		this.scenario.submit(Mail.from(SENDER).header("Auto-Submitted", "auto-replied").body("Out of office"));
		var first = this.scenario.submit(Mail.from(SENDER).subject("Car accident").body("My claim. " + marker));
		this.scenario.awaitFiled(first);
		this.scenario.submit(Mail.from(SENDER).messageId(first).subject("Car accident").body("My claim. " + marker));

		Awaitility.await().atMost(IntakeScenario.WAIT).until(() -> count("claim.intake.emails", "outcome", "duplicate") == duplicates + 1);

		assertThat(count("claim.intake.emails", "outcome", "skipped_auto_reply")).isEqualTo(skipped + 1);
	}

	@Test
	void aFollowUpFromAnotherAddressIsCountedAsNoMatchingClaim() {
		var before = count("claim.intake.emails", "outcome", "no_matching_claim");
		var seeded = this.scenario.claim(1L);
		var marker = marker();
		classify(marker, EmailType.CLAIM_FOLLOW_UP);

		this.scenario.submit(Mail.from("stranger@example.com").subject("About " + seeded.claimNumber).body("Tell me. " + marker));

		awaitOutcome("no_matching_claim", before + 1);
	}

	@Test
	void anUnsureResolverAnswerIsCountedAsNeedingClarification() {
		var before = count("claim.intake.emails", "outcome", "needs_clarification");
		createClaim(SENDER, NO_LOCATION);
		var marker = marker();
		this.stubs.answerWhenAll(List.of(RESOLVER, marker), """
			{"kind":"UNSURE","claimNumber":null}""");

		this.scenario.submit(Mail.from(SENDER).subject("Update").body("Some news. " + marker));

		awaitOutcome("needs_clarification", before + 1);
	}

	@Test
	void aPolicyOfAnotherCustomerIsCountedAsRejected() {
		var before = count("claim.intake.emails", "outcome", "policy_rejected");
		var newClaims = count("claim.intake.emails", "outcome", "new_claim");
		var marker = marker();
		stubNewClaim(marker, details(true, "2026-10-07", "Elm Street", "SINGLE_VEHICLE", "AC-987654321", List.of()));

		this.scenario.submit(Mail.from(SENDER).name(SENDER_NAME).subject("My accident").body("Policy AC-987654321. " + marker));

		awaitOutcome("policy_rejected", before + 1);
		assertThat(count("claim.intake.emails", "outcome", "new_claim")).isEqualTo(newClaims);
	}

	@Test
	void aReplyHandedBackWhileTheReviewIsBeingDecidedIsCountedOnceByItsFinalOutcome() {
		var updates = count("claim.intake.emails", "outcome", "claim_update");
		var statusQuestions = count("claim.intake.emails", "outcome", "status_question");
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		var run = this.scenario.awaitWaitingRun(created.claim().id);
		var runId = this.scenario.claimReview(created.claim().id);
		var marker = marker();
		stubFollowUp(marker, COMPLETE_DETAILS);

		this.scenario.submit(Mail.from(SENDER).inReplyTo(created.messageId()).body("One more thing. " + marker));
		// The reply's first attempt ran the agents and found the claim being decided, so it was handed back
		Awaitility.await().atMost(IntakeScenario.WAIT).until(() -> this.stubs.countRequestsFor(marker) > 0);
		this.scenario.publishUntilWoken(run, new ReviewDecided(created.claim().id, runId, new ReviewDecision(ReviewOutcome.READY, Set.of(), ReviewedFields.none())));

		awaitOutcome("status_question", statusQuestions + 1);
		assertThat(count("claim.intake.emails", "outcome", "claim_update")).isEqualTo(updates);
	}

	@Test
	void aDecisionThatCouldNotBeAppliedIsCountedAsRecovered() {
		var recovered = counted("claim.intake.failures", "stage", "unknown", "exception", "IllegalStateException", "outcome", "recovered");
		var fatal = count("claim.intake.failures", "outcome", "fatal");
		var claim = createClaim(SENDER, COMPLETE_DETAILS).claim();
		this.scenario.awaitWaitingRun(claim.id);
		doThrow(new IllegalStateException("The database is down")).doCallRealMethod().when(this.decisionSteps).applyReady(any(), any());

		this.scenario.decide(claim.id, ReviewOutcome.READY, Set.of());

		awaitCounted(recovered + 1, "claim.intake.failures", "stage", "unknown", "exception", "IllegalStateException", "outcome", "recovered");
		assertThat(count("claim.intake.failures", "outcome", "fatal")).isEqualTo(fatal);
	}

	@Test
	void aDecisionWhoseEmailCouldNotBeSentIsCountedAsRecovered() {
		var recovered = counted("claim.intake.failures", "stage", "unknown", "exception", "IllegalStateException", "outcome", "recovered");
		var claim = createClaim(SENDER, COMPLETE_DETAILS).claim();
		this.scenario.awaitWaitingRun(claim.id);
		doThrow(new IllegalStateException("The SMTP server is down")).when(this.sender).sendTemplateReply(any(), any(), any(), any(), any());

		this.scenario.decide(claim.id, ReviewOutcome.READY, Set.of());

		awaitCounted(recovered + 1, "claim.intake.failures", "stage", "unknown", "exception", "IllegalStateException", "outcome", "recovered");
		assertThat(this.scenario.claim(claim.id).status).isEqualTo("In Process");
	}

	@Test
	void anAgentFailureIsCountedAsFatalInTheAgentStage() {
		var failedEmails = counted("claim.intake.emails", "outcome", "failed");
		var failures = fatalFailuresByException("agent");

		var id = this.scenario.submit(Mail.from(SENDER).subject("Car accident").body("Nothing is stubbed for " + marker()));
		this.scenario.awaitFailed(id);

		awaitCounted(failedEmails + 1, "claim.intake.emails", "outcome", "failed");
		assertFatalFailureIncrement(id, "agent", failures);
	}

	@Test
	void anEmailThatIsNoLongerInTheProcessingFolderIsCountedAsFatalInTheFetchStage() {
		var failures = fatalFailuresByException("fetch");
		var email = this.scenario.deliverToProcessing(Mail.from(SENDER).subject("Car accident").body("Gone before it's read. " + marker()));
		this.mailbox.move(email.messageId().orElseThrow(), MailFolder.PROCESSING, MailFolder.PROCESSED);

		this.scenario.submit(email);

		assertFatalFailureIncrement(email.messageId().orElseThrow(), "fetch", failures);
	}

	@Test
	void aFailureToPersistTheClaimIsCountedAsFatalInThePersistStage() {
		var failures = fatalFailuresByException("persist");
		var marker = marker();
		stubNewClaim(marker, COMPLETE_DETAILS);
		doThrow(new PersistenceException("The database is down")).when(this.applying).apply(any(), any(), any());

		var id = this.scenario.submit(Mail.from(SENDER).subject("Car accident").body("My claim. " + marker));
		this.scenario.awaitFailed(id);

		assertFatalFailureIncrement(id, "persist", failures);
	}

	@Test
	void aNewClaimRecordsItsLatencySizeAttachmentsClassificationAndExtraction() {
		var latency = timerCount("claim.intake.email.latency", "outcome", "new_claim");
		var sizes = summaryCount("claim.intake.email.size");
		var kept = counted("claim.intake.attachments", "result", "kept", "reason", "none");
		var notImages = counted("claim.intake.attachments", "result", "skipped", "reason", "not_an_image");
		var classified = counted("claim.intake.classifications", "email_type", "new_claim", "matched_claim", "none");
		var noMatch = counted("claim.intake.matcher.result", "result", "no_match");
		var completeness = summaryCount("claim.intake.extraction.completeness");
		var completenessTotal = summaryTotal("claim.intake.extraction.completeness");
		var missing = counted("claim.intake.missing.items");
		var marker = marker();
		stubNewClaim(marker, COMPLETE_DETAILS);

		var id = this.scenario.submit(Mail.from(SENDER).name(SENDER_NAME).subject("Car accident").body("First email. " + marker)
			.attachment("front.jpg", "image/jpeg", JPEG)
			.attachment("report.pdf", "application/pdf", new byte[] { '%', 'P', 'D', 'F' }));
		this.scenario.awaitFiled(id);

		Awaitility.await().atMost(IntakeScenario.WAIT).untilAsserted(() -> {
			assertThat(timerCount("claim.intake.email.latency", "outcome", "new_claim")).isEqualTo(latency + 1);
			assertThat(summaryCount("claim.intake.email.size")).isEqualTo(sizes + 1);
			assertThat(counted("claim.intake.attachments", "result", "kept", "reason", "none")).isEqualTo(kept + 1);
			assertThat(counted("claim.intake.attachments", "result", "skipped", "reason", "not_an_image")).isEqualTo(notImages + 1);
			assertThat(counted("claim.intake.classifications", "email_type", "new_claim", "matched_claim", "none")).isEqualTo(classified + 1);
			assertThat(counted("claim.intake.matcher.result", "result", "no_match")).isEqualTo(noMatch + 1);
			assertThat(summaryCount("claim.intake.extraction.completeness")).isEqualTo(completeness + 1);
			assertThat(summaryTotal("claim.intake.extraction.completeness")).isEqualTo(completenessTotal + 1.0);
		});
		assertThat(counted("claim.intake.missing.items")).isEqualTo(missing);
		assertThat(totalTime("claim.intake.email.latency", "outcome", "new_claim")).isPositive();
		assertThat(summaryTotal("claim.intake.email.size")).isPositive();
	}

	@Test
	void aClaimWithoutALocationIsLessCompleteAndCountsTheItemItLacks() {
		var completeness = summaryCount("claim.intake.extraction.completeness");
		var completenessTotal = summaryTotal("claim.intake.extraction.completeness");
		var locations = counted("claim.intake.missing.items", "item", "location");
		var others = counted("claim.intake.missing.items") - locations;

		createClaim(SENDER, NO_LOCATION);

		Awaitility.await().atMost(IntakeScenario.WAIT).untilAsserted(() -> {
			assertThat(summaryCount("claim.intake.extraction.completeness")).isEqualTo(completeness + 1);
			assertThat(summaryTotal("claim.intake.extraction.completeness")).isEqualTo(completenessTotal + 0.75);
			assertThat(counted("claim.intake.missing.items", "item", "location")).isEqualTo(locations + 1);
		});
		assertThat(counted("claim.intake.missing.items") - counted("claim.intake.missing.items", "item", "location")).isEqualTo(others);
	}

	@Test
	void aReplyOnAPendingClaimIsMatchedClassifiedAndNotCountedAsANewClaimExtraction() {
		var created = createClaim(SENDER, NO_LOCATION);
		this.scenario.awaitReplies(SENDER, 1);
		var matched = counted("claim.intake.matcher.result", "result", "matched");
		var classified = counted("claim.intake.classifications", "email_type", "claim_follow_up", "matched_claim", "pending");
		var latency = timerCount("claim.intake.email.latency", "outcome", "claim_update");
		var extractions = summaryCount("claim.intake.extraction.completeness");
		var marker = marker();
		stubFollowUp(marker, COMPLETE_DETAILS);

		this.scenario.submit(Mail.from(SENDER).inReplyTo(created.messageId()).body("It was on Elm Street. " + marker));

		Awaitility.await().atMost(IntakeScenario.WAIT).untilAsserted(() -> {
			assertThat(counted("claim.intake.matcher.result", "result", "matched")).isEqualTo(matched + 1);
			assertThat(counted("claim.intake.classifications", "email_type", "claim_follow_up", "matched_claim", "pending")).isEqualTo(classified + 1);
			assertThat(timerCount("claim.intake.email.latency", "outcome", "claim_update")).isEqualTo(latency + 1);
		});
		assertThat(summaryCount("claim.intake.extraction.completeness")).isEqualTo(extractions);
	}

	@Test
	void theResolverOutcomesAreCountedApart() {
		var created = createClaim(SENDER, NO_LOCATION);
		var existing = counted("claim.intake.matcher.result", "result", "resolved_existing");
		var newIncident = counted("claim.intake.matcher.result", "result", "resolved_new_incident");
		var unsure = counted("claim.intake.matcher.result", "result", "unsure");
		var existingMarker = marker();
		var newIncidentMarker = marker();
		var unsureMarker = marker();
		stubFollowUp(existingMarker, COMPLETE_DETAILS);
		this.stubs.answerWhenAll(List.of(RESOLVER, existingMarker), """
			{"kind":"EXISTING","claimNumber":"%s"}""".formatted(created.claim().claimNumber));
		stubNewClaim(newIncidentMarker, COMPLETE_DETAILS);
		this.stubs.answerWhenAll(List.of(RESOLVER, newIncidentMarker), """
			{"kind":"NEW_INCIDENT","claimNumber":null}""");
		this.stubs.answerWhenAll(List.of(RESOLVER, unsureMarker), """
			{"kind":"UNSURE","claimNumber":null}""");

		this.scenario.submit(Mail.from(SENDER).subject("Update").body("It was on Elm Street. " + existingMarker));
		awaitCounted(existing + 1, "claim.intake.matcher.result", "result", "resolved_existing");
		this.scenario.submit(Mail.from(SENDER).subject("Another").body("Something else happened. " + newIncidentMarker));
		awaitCounted(newIncident + 1, "claim.intake.matcher.result", "result", "resolved_new_incident");
		this.scenario.submit(Mail.from(SENDER).subject("Hmm").body("Some news. " + unsureMarker));
		awaitCounted(unsure + 1, "claim.intake.matcher.result", "result", "unsure");
	}

	@Test
	void aWrongSenderIsCountedAsSuch() {
		var wrongSender = counted("claim.intake.matcher.result", "result", "wrong_sender");
		var seeded = this.scenario.claim(1L);
		var marker = marker();
		classify(marker, EmailType.CLAIM_FOLLOW_UP);

		this.scenario.submit(Mail.from("stranger@example.com").subject("About " + seeded.claimNumber).body("Tell me. " + marker));

		awaitCounted(wrongSender + 1, "claim.intake.matcher.result", "result", "wrong_sender");
	}

	@Test
	void aRequeuedEmailIsClassifiedAndMatchedAgainButCountedOnce() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		var run = this.scenario.awaitWaitingRun(created.claim().id);
		var runId = this.scenario.claimReview(created.claim().id);
		var classified = counted("claim.intake.classifications", "email_type", "claim_follow_up");
		var matched = counted("claim.intake.matcher.result", "result", "matched");
		var statusQuestions = counted("claim.intake.emails", "outcome", "status_question");
		var marker = marker();
		stubFollowUp(marker, COMPLETE_DETAILS);

		this.scenario.submit(Mail.from(SENDER).inReplyTo(created.messageId()).body("One more thing. " + marker));
		Awaitility.await().atMost(IntakeScenario.WAIT).until(() -> this.stubs.countRequestsFor(marker) > 0);
		this.scenario.publishUntilWoken(run, new ReviewDecided(created.claim().id, runId, new ReviewDecision(ReviewOutcome.READY, Set.of(), ReviewedFields.none())));

		awaitCounted(statusQuestions + 1, "claim.intake.emails", "outcome", "status_question");
		// The first evaluation found the claim pending, the rerun found it In Process: both ran the agents
		assertThat(counted("claim.intake.classifications", "email_type", "claim_follow_up")).isEqualTo(classified + 2);
		assertThat(counted("claim.intake.matcher.result", "result", "matched")).isEqualTo(matched + 2);
		assertThat(counted("claim.intake.emails", "outcome", "status_question")).isEqualTo(statusQuestions + 1);
	}

	@Test
	void readyWithoutChangingAFieldAgreesAndCompletesTheClaim() {
		var agreed = counted("claim.intake.review.overrides", "agreement", "agreed");
		var overridden = counted("claim.intake.review.overrides", "agreement", "overridden");
		var emails = summaryCount("claim.intake.claim.emails");
		var emailsTotal = summaryTotal("claim.intake.claim.emails");
		var completions = this.registry.timer("claim.intake.claim.time.to.complete").count();
		var claim = createClaim(SENDER, COMPLETE_DETAILS).claim();

		this.scenario.decide(claim.id, ReviewOutcome.READY, Set.of());

		Awaitility.await().atMost(IntakeScenario.WAIT).untilAsserted(() -> {
			assertThat(counted("claim.intake.review.overrides", "agreement", "agreed")).isEqualTo(agreed + 1);
			assertThat(summaryCount("claim.intake.claim.emails")).isEqualTo(emails + 1);
			assertThat(summaryTotal("claim.intake.claim.emails")).isEqualTo(emailsTotal + 1);
			assertThat(this.registry.timer("claim.intake.claim.time.to.complete").count()).isEqualTo(completions + 1);
		});
		assertThat(counted("claim.intake.review.overrides", "agreement", "overridden")).isEqualTo(overridden);
	}

	@Test
	void readyWithAChangedFieldIsAnOverride() {
		var agreed = counted("claim.intake.review.overrides", "agreement", "agreed");
		var overridden = counted("claim.intake.review.overrides", "agreement", "overridden");
		var claim = createClaim(SENDER, COMPLETE_DETAILS).claim();

		this.scenario.decide(claim.id, ReviewOutcome.READY, Set.of(), new ReviewedFields("Car hit a tree.", LocalDate.of(2026, 10, 7), null, "Elm Street", ClaimCategory.SINGLE_VEHICLE));

		awaitCounted(overridden + 1, "claim.intake.review.overrides", "agreement", "overridden");
		assertThat(counted("claim.intake.review.overrides", "agreement", "agreed")).isEqualTo(agreed);
	}

	@Test
	void askingTheCustomerForMoreIsAnOverrideAndDoesNotCompleteTheClaim() {
		var overridden = counted("claim.intake.review.overrides", "agreement", "overridden");
		var emails = summaryCount("claim.intake.claim.emails");
		var completions = this.registry.timer("claim.intake.claim.time.to.complete").count();
		var claim = createClaim(SENDER, COMPLETE_DETAILS).claim();

		this.scenario.decide(claim.id, ReviewOutcome.NEEDS_INFORMATION, Set.of(MissingItem.LOCATION));

		awaitCounted(overridden + 1, "claim.intake.review.overrides", "agreement", "overridden");
		assertThat(summaryCount("claim.intake.claim.emails")).isEqualTo(emails);
		assertThat(this.registry.timer("claim.intake.claim.time.to.complete").count()).isEqualTo(completions);
	}

	@Test
	void aFailedEmailStillInTheProcessingFolderCountsItsLatency() {
		var latency = timerCount("claim.intake.email.latency", "outcome", "failed");

		var id = this.scenario.submit(Mail.from(SENDER).subject("Car accident").body("Nothing is stubbed for " + marker()));
		this.scenario.awaitFailed(id);

		Awaitility.await().atMost(IntakeScenario.WAIT).until(() -> timerCount("claim.intake.email.latency", "outcome", "failed") == latency + 1);
	}

	@Test
	void anEmailThatWasAlreadyFiledCountsNoLatencyWhenItsRunFails() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		var latency = timerCount("claim.intake.email.latency", "outcome", "failed");
		var failedEmails = counted("claim.intake.emails", "outcome", "failed");

		// The email is in the processed folder, as it is when a run fails after its review wait
		this.failureHandler.handle(created.messageId(), new IllegalStateException("The run faulted after its review wait"));

		awaitCounted(failedEmails + 1, "claim.intake.emails", "outcome", "failed");
		assertThat(timerCount("claim.intake.email.latency", "outcome", "failed")).isEqualTo(latency);
	}

	@Test
	void mailboxCallsAreTimedAndTheirLockWaitsRecorded() {
		var reads = timerCount("claim.intake.mailbox.lock.wait", "mode", "read");
		var writes = timerCount("claim.intake.mailbox.lock.wait", "mode", "write");
		var lists = timerCount("claim.intake.mail.client.duration", "protocol", "imap", "operation", "list", "outcome", "success");
		var moves = timerCount("claim.intake.mail.client.duration", "protocol", "imap", "operation", "move", "outcome", "success");

		this.mailbox.list(MailFolder.INBOX);
		this.mailbox.move("<nobody@example.com>", MailFolder.PROCESSING, MailFolder.PROCESSED);

		assertThat(timerCount("claim.intake.mailbox.lock.wait", "mode", "read")).isEqualTo(reads + 1);
		assertThat(timerCount("claim.intake.mailbox.lock.wait", "mode", "write")).isEqualTo(writes + 1);
		assertThat(timerCount("claim.intake.mail.client.duration", "protocol", "imap", "operation", "list", "outcome", "success")).isEqualTo(lists + 1);
		assertThat(timerCount("claim.intake.mail.client.duration", "protocol", "imap", "operation", "move", "outcome", "success")).isEqualTo(moves + 1);
	}

	@Test
	void aReplyIsTimedAsAnSmtpCall() {
		var sends = timerCount("claim.intake.mail.client.duration", "protocol", "smtp", "outcome", "success");

		createClaim(SENDER, NO_LOCATION);
		this.scenario.awaitReplies(SENDER, 1);

		Awaitility.await().atMost(IntakeScenario.WAIT).until(() -> timerCount("claim.intake.mail.client.duration", "protocol", "smtp", "outcome", "success") > sends);
	}

	@Test
	void theInboxDepthGaugeReadsTheLastMeasurement() {
		this.activities.publish(new InboxDepthMeasured(7));
		assertThat(this.registry.get("claim.intake.inbox.depth").gauge().value()).isEqualTo(7);

		this.activities.publish(new InboxDepthMeasured(0));
		assertThat(this.registry.get("claim.intake.inbox.depth").gauge().value()).isZero();
	}

	@Test
	void theOldestPendingReviewGaugeFollowsTheClaimThatHasWaitedLongest() {
		assertThat(this.registry.get("claim.intake.review.oldest.pending").gauge().value()).isZero();

		var claim = createClaim(SENDER, COMPLETE_DETAILS).claim();
		this.scenario.awaitWaitingRun(claim.id);
		var openedAt = this.scenario.intake(claim.id).orElseThrow().reviewOpenedAt;

		assertThat(this.registry.get("claim.intake.review.oldest.pending").gauge().value())
			.isBetween(0.0, Duration.between(openedAt, Instant.now()).toMillis() / 1000.0 + 1);

		this.scenario.decide(claim.id, ReviewOutcome.READY, Set.of());
		Awaitility.await().atMost(IntakeScenario.WAIT).until(() -> this.registry.get("claim.intake.review.oldest.pending").gauge().value() == 0);
	}

	@Test
	void rolledBackExtractionAndCompletionEventsDoNotUpdateMetersButCommittedEventsDo() {
		var extractions = summaryCount("claim.intake.extraction.completeness");
		var missing = counted("claim.intake.missing.items", "item", "category");
		var emails = summaryCount("claim.intake.claim.emails");
		var completions = this.registry.timer("claim.intake.claim.time.to.complete").count();

		assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
			this.activities.publish(new NewClaimExtracted(0.75, Set.of(MissingItem.CATEGORY)));
			this.activities.publish(new ClaimCompleted(Duration.ofMinutes(5), 2));
			QuarkusTransaction.setRollbackOnly();
		}))
			.isInstanceOf(io.quarkus.narayana.jta.QuarkusTransactionException.class)
			.hasCauseInstanceOf(jakarta.transaction.RollbackException.class);
		assertThat(summaryCount("claim.intake.extraction.completeness")).isEqualTo(extractions);
		assertThat(counted("claim.intake.missing.items", "item", "category")).isEqualTo(missing);
		assertThat(summaryCount("claim.intake.claim.emails")).isEqualTo(emails);
		assertThat(this.registry.timer("claim.intake.claim.time.to.complete").count()).isEqualTo(completions);

		QuarkusTransaction.requiringNew().run(() -> {
			this.activities.publish(new NewClaimExtracted(0.75, Set.of(MissingItem.CATEGORY)));
			this.activities.publish(new ClaimCompleted(Duration.ofMinutes(5), 2));
		});
		assertThat(summaryCount("claim.intake.extraction.completeness")).isEqualTo(extractions + 1);
		assertThat(counted("claim.intake.missing.items", "item", "category")).isEqualTo(missing + 1);
		assertThat(summaryCount("claim.intake.claim.emails")).isEqualTo(emails + 1);
		assertThat(this.registry.timer("claim.intake.claim.time.to.complete").count()).isEqualTo(completions + 1);
		assertThat(this.registry.timer("claim.intake.claim.time.to.complete").totalTime(TimeUnit.MINUTES)).isGreaterThanOrEqualTo(5.0);
	}

	@Test
	void aRolledBackOverrideIsNotCounted() {
		var overridden = counted("claim.intake.review.overrides", "agreement", "overridden");

		assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
			this.activities.publish(new ReviewDecisionApplied(ReviewOutcome.READY, ReviewAgreement.OVERRIDDEN, Optional.empty()));
			QuarkusTransaction.setRollbackOnly();
		}))
			.isInstanceOf(io.quarkus.narayana.jta.QuarkusTransactionException.class);
		assertThat(counted("claim.intake.review.overrides", "agreement", "overridden")).isEqualTo(overridden);

		QuarkusTransaction.requiringNew().run(() -> this.activities.publish(new ReviewDecisionApplied(ReviewOutcome.READY, ReviewAgreement.OVERRIDDEN, Optional.empty())));
		assertThat(counted("claim.intake.review.overrides", "agreement", "overridden")).isEqualTo(overridden + 1);
	}

	private long timerCount(String name, String... tags) {
		return this.registry.find(name)
			.tags(tags)
			.timers()
			.stream()
			.mapToLong(Timer::count)
			.sum();
	}

	private double totalTime(String name, String... tags) {
		return this.registry.find(name)
			.tags(tags)
			.timers()
			.stream()
			.mapToDouble(timer -> timer.totalTime(TimeUnit.NANOSECONDS))
			.sum();
	}

	private long summaryCount(String name) {
		return this.registry.find(name)
			.summaries()
			.stream()
			.mapToLong(DistributionSummary::count)
			.sum();
	}

	private double summaryTotal(String name) {
		return this.registry.find(name)
			.summaries()
			.stream()
			.mapToDouble(DistributionSummary::totalAmount)
			.sum();
	}

	@Test
	void meterTagsHaveOnlyTheirDeclaredKeysAndBoundedValuesAndNoIdentifiers() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		var conversationId = this.scenario.intake(created.claim().id).orElseThrow().intakeConversationId;
		var forbidden = Set.of(SENDER, created.claim().claimNumber, created.messageId(), conversationId);
		// Derive template names from their declarations, not a second list that can drift from Qute.
		var templates = Stream.concat(
				Arrays.stream(IntakeTemplates.class.getDeclaredMethods())
					.filter(method -> method.getReturnType().equals(MailTemplateInstance.class))
					.map(Method::getName),
				Stream.of("status_answer"))
			.collect(Collectors.toUnmodifiableSet());
		var schemas = Map.ofEntries(
			Map.entry("claim.intake.emails", Map.of("outcome", Arrays.stream(EmailOutcome.values()).map(EmailOutcome::tag).collect(Collectors.toSet()))),
			Map.entry("claim.intake.claim.status.transitions", Map.of("status", Arrays.stream(IntakeClaimStatus.values()).map(IntakeClaimStatus::label).collect(Collectors.toSet()))),
			Map.entry("claim.intake.replies", Map.of("template", templates)),
			Map.entry("claim.intake.review.decisions", Map.of("decision", Arrays.stream(ReviewOutcome.values())
				.map(outcome -> outcome.name().toLowerCase(Locale.ROOT))
				.collect(Collectors.toSet()))),
			Map.entry("claim.intake.review.wait", Map.<String, Set<String>>of()),
			Map.entry("claim.intake.failures", Map.of("stage", Arrays.stream(FailureStage.values()).map(FailureStage::tag).collect(Collectors.toSet()),
				"outcome", Arrays.stream(FailureOutcome.values()).map(FailureOutcome::tag).collect(Collectors.toSet()), "exception", Set.<String>of())),
			Map.entry("claim.intake.reviews.pending", Map.<String, Set<String>>of()),
			Map.entry("claim.intake.watcher.connected", Map.<String, Set<String>>of()),
			Map.entry("claim.intake.watcher.reconnects", Map.<String, Set<String>>of()),
			Map.entry("claim.intake.email.latency", Map.of("outcome", Arrays.stream(EmailOutcome.values()).map(EmailOutcome::tag).collect(Collectors.toSet()))),
			Map.entry("claim.intake.attachments", Map.of("result", Set.of("kept", "skipped"),
				"reason", Stream.concat(Stream.of("none"), Arrays.stream(SkippedAttachment.Reason.values()).map(reason -> reason.name().toLowerCase(Locale.ROOT))).collect(Collectors.toSet()))),
			Map.entry("claim.intake.email.size", Map.<String, Set<String>>of()),
			Map.entry("claim.intake.classifications", Map.of("email_type", Arrays.stream(EmailType.values()).map(type -> type.name().toLowerCase(Locale.ROOT)).collect(Collectors.toSet()),
				"matched_claim", Arrays.stream(MatchedClaimKind.values()).map(MatchedClaimKind::tag).collect(Collectors.toSet()))),
			Map.entry("claim.intake.matcher.result", Map.of("result", Arrays.stream(MatcherResult.values()).map(MatcherResult::tag).collect(Collectors.toSet()))),
			Map.entry("claim.intake.extraction.completeness", Map.<String, Set<String>>of()),
			Map.entry("claim.intake.missing.items", Map.of("item", Arrays.stream(MissingItem.values()).map(item -> item.name().toLowerCase(Locale.ROOT)).collect(Collectors.toSet()))),
			Map.entry("claim.intake.claim.emails", Map.<String, Set<String>>of()),
			Map.entry("claim.intake.claim.time.to.complete", Map.<String, Set<String>>of()),
			Map.entry("claim.intake.review.overrides", Map.of("agreement", Arrays.stream(ReviewAgreement.values()).map(ReviewAgreement::tag).collect(Collectors.toSet()))),
			Map.entry("claim.intake.mail.client.duration", Map.of("protocol", Set.of("imap", "smtp"), "operation", Set.<String>of(), "outcome", Set.of("success", "error"))),
			Map.entry("claim.intake.mailbox.lock.wait", Map.of("mode", Arrays.stream(MailboxLockMode.values()).map(MailboxLockMode::tag).collect(Collectors.toSet()))),
			Map.entry("claim.intake.inbox.depth", Map.<String, Set<String>>of()),
			Map.entry("claim.intake.review.oldest.pending", Map.<String, Set<String>>of()));
		var meters = this.registry.getMeters().stream()
			.filter(meter -> meter.getId().getName().startsWith("claim.intake."))
			.toList();

		assertThat(meters).isNotEmpty().allSatisfy(meter -> {
			var id = meter.getId();
			assertThat(schemas).containsKey(id.getName());
			var schema = schemas.get(id.getName());
			assertThat(id.getTags()).extracting(Tag::getKey).containsExactlyInAnyOrderElementsOf(schema.keySet());
			assertThat(id.getTags()).allSatisfy(tag -> {
				assertThat(forbidden).doesNotContain(tag.getValue());
				if (tag.getKey().equals("exception") || tag.getKey().equals("operation")) {
					// Exceptions and mail operations are open-ended, not an enum; failure scenarios check the actual boundary's class separately.
					assertThat(tag.getValue()).matches("[A-Za-z_$][A-Za-z0-9_$]*");
				}
				else {
					assertThat(schema.get(tag.getKey())).contains(tag.getValue());
				}
			});
		});
	}

	private Map<String, Double> fatalFailuresByException(String stage) {
		return this.registry.find("claim.intake.failures")
			.tags("stage", stage, "outcome", "fatal")
			.counters()
			.stream()
			.collect(Collectors.toMap(counter -> counter.getId().getTag("exception"), Counter::count));
	}

	private void assertFatalFailureIncrement(String messageId, String stage, Map<String, Double> before) {
		Awaitility.await()
			.atMost(IntakeScenario.WAIT)
			.untilAsserted(() -> {
				// Flow may wrap the collaborator's exception. Assert the exact class delivered to the failure boundary,
				// not a guessed leaf type; snapshot every exception series before the run to retain a real delta.
				var cause = ArgumentCaptor.forClass(Throwable.class);
				verify(this.failureHandler).handle(eq(messageId), cause.capture());
				var exception = cause.getValue().getClass().getSimpleName();
				assertThat(this.registry.find("claim.intake.failures")
					.tags("stage", stage, "exception", exception, "outcome", "fatal")
					.counter())
					.isNotNull()
					.satisfies(counter -> assertThat(counter.count()).isEqualTo(before.getOrDefault(exception, 0.0) + 1));
			});
	}

	private void awaitOutcome(String outcome, double expected) {
		Awaitility.await()
			.atMost(IntakeScenario.WAIT)
			.until(() -> count("claim.intake.emails", "outcome", outcome) == expected);
	}

	private double count(String name, String tag, String value) {
		return this.registry.find(name)
			.tag(tag, value)
			.counters()
			.stream()
			.mapToDouble(Counter::count)
			.sum();
	}

	private double pendingReviewsGauge() {
		return this.registry.get("claim.intake.reviews.pending")
			.gauge()
			.value();
	}
}
