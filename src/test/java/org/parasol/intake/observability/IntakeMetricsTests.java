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
import org.parasol.claim.model.IntakeClaimStatus;
import org.parasol.intake.IntakeFlowTestBase;
import org.parasol.intake.IntakeScenario;
import org.parasol.intake.IntakeScenario.Mail;
import org.parasol.intake.MissingItem;
import org.parasol.intake.failure.IntakeFailureHandler;
import org.parasol.intake.mailbox.ClaimsMailbox;
import org.parasol.intake.mailbox.MailFolder;
import org.parasol.intake.model.EmailType;
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
	private static final String NO_LOCATION = details("Car hit a post.", "2026-10-07", null, "SINGLE_VEHICLE", null, List.of());

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
			this.activities.publish(new ReviewDecisionApplied(ReviewOutcome.READY, Optional.of(waited)));
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
			this.activities.publish(new ReviewDecisionApplied(ReviewOutcome.READY, Optional.of(waited)));
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
		stubNewClaim(marker, details("Car hit a post.", "2026-10-07", "Elm Street", "SINGLE_VEHICLE", "AC-987654321", List.of()));

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
		var schemas = Map.of(
			"claim.intake.emails", Map.of("outcome", Arrays.stream(EmailOutcome.values()).map(EmailOutcome::tag).collect(Collectors.toSet())),
			"claim.intake.claim.status.transitions", Map.of("status", Arrays.stream(IntakeClaimStatus.values()).map(IntakeClaimStatus::label).collect(Collectors.toSet())),
			"claim.intake.replies", Map.of("template", templates),
			"claim.intake.review.decisions", Map.of("decision", Arrays.stream(ReviewOutcome.values())
				.map(outcome -> outcome.name().toLowerCase(Locale.ROOT))
				.collect(Collectors.toSet())),
			"claim.intake.review.wait", Map.<String, Set<String>>of(),
			"claim.intake.failures", Map.of("stage", Arrays.stream(FailureStage.values()).map(FailureStage::tag).collect(Collectors.toSet()),
				"outcome", Arrays.stream(FailureOutcome.values()).map(FailureOutcome::tag).collect(Collectors.toSet()), "exception", Set.<String>of()),
			"claim.intake.reviews.pending", Map.<String, Set<String>>of(),
			"claim.intake.watcher.connected", Map.<String, Set<String>>of(),
			"claim.intake.watcher.reconnects", Map.<String, Set<String>>of());
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
				if (tag.getKey().equals("exception")) {
					// Exceptions are open-ended, not an enum; failure scenarios check the actual boundary's class separately.
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
