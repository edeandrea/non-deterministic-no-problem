package org.parasol.intake.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import jakarta.inject.Inject;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.parasol.intake.IntakeFlowTestBase;
import org.parasol.intake.IntakeScenario;
import org.parasol.intake.IntakeScenario.Mail;
import org.parasol.intake.StartedRuns;
import org.parasol.intake.StartedRuns.StartedRun;
import org.parasol.intake.TestSpans;
import org.parasol.intake.model.EmailType;
import org.parasol.intake.observability.SpanCensus.Kind;
import org.parasol.intake.review.ReviewOutcome;
import org.parasol.intake.workflow.ClaimIntakeFlow;
import org.parasol.intake.workflow.review.ReviewDecisionSteps;
import org.parasol.testing.StubbedModelsTestProfile;

import ai.scoring.langfuse.otel.LangfuseFlowSpanTypingProcessor;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes;
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GenAiOperationNameIncubatingValues;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.serverlessworkflow.impl.WorkflowStatus;

/**
 * Does the conversation id the starter enters ({@code ConversationContext.runIn}) reach every span of a run, with no
 * adapter, on quarkus-flow 1.3.0? The run's Flow spans, the AI calls from its steps and under the agent sub-workflows Flow
 * generates, and the JDBC statements that have a parent; with several runs at once, and after a review run wakes up.
 * <p>
 *   Each test prints a {@link SpanCensus} (counts by span kind and id status) before it asserts. A run's expected id comes
 *   from its input ({@link StartedRuns}), never from a span.
 * </p>
 */
@QuarkusTest
@TestProfile(StubbedModelsTestProfile.class)
class IntakeSpanPropagationTests extends IntakeFlowTestBase {
	private static final int CONCURRENT_RUNS = 8;
	// No location, so each run asks for it and ends instead of waiting for review, and all its spans are exported
	private static final String INCOMPLETE_DETAILS = details(true, "2026-10-07", null, "SINGLE_VEHICLE", null, List.of());
	// Keeps every run's model calls in flight long enough for the batch's runs to overlap
	private static final int MODEL_DELAY_MS = 400;
	private static final String OTHER_SENDER = "john.roe@example.com";
	private static final String NOT_A_CLAIM_SENDER = "newsletter.reader@example.com";

	@Inject
	StartedRuns startedRuns;

	@InjectSpy
	ReviewDecisionSteps decisionSteps;

	@BeforeEach
	void resetRuns() {
		this.startedRuns.reset();
	}

	@Test
	void eightConcurrentRunsEachCarryOnlyTheirOwnIdAndALaterBatchNoneOfThem() {
		var first = runConcurrently("first");
		var firstSpans = this.spans.all();
		var firstIds = ids(first);
		var firstCensus = SpanCensus.of(firstSpans, first, Set.of());
		Log.infof("%s", firstCensus.render("%d concurrent new-claim runs".formatted(CONCURRENT_RUNS)));

		var exportedBefore = firstSpans.stream()
			.map(SpanData::getSpanId)
			.collect(Collectors.toSet());
		var second = runConcurrently("second");
		var secondSpans = this.spans.all()
			.stream()
			.filter(span -> !exportedBefore.contains(span.getSpanId()))
			.toList();
		var secondCensus = SpanCensus.of(secondSpans, second, firstIds);
		Log.infof("%s", secondCensus.render("a following batch of %d unmatched runs".formatted(CONCURRENT_RUNS)));

		assertThat(ids(second)).as("an unmatched email gets a fresh id").hasSize(CONCURRENT_RUNS).doesNotContainAnyElementsOf(firstIds);
		assertCarriesOnlyOwnIds(first, firstCensus);
		assertCarriesOnlyOwnIds(second, secondCensus);
	}

	@Test
	void aClaimsEmailsAndItsDecisionShareOneIdAndOtherEmailsGetTheirOwn() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		var claimId = created.claim().id;
		var conversationId = this.scenario.intake(claimId).orElseThrow().intakeConversationId;
		var firstRunId = this.scenario.awaitWaitingRun(claimId).id();

		// The follow-up supersedes the waiting review: its run cancels the first one and waits in its place
		var marker = marker();
		stubFollowUp(marker, COMPLETE_DETAILS.replace("Elm Street", "Oak Avenue"));
		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).inReplyTo(created.messageId()).body("Actually Oak Avenue. " + marker)));
		var reviewRunId = Awaitility.await().atMost(IntakeScenario.WAIT)
			.until(() -> this.scenario.waitingRun(claimId).filter(run -> !run.id().equals(firstRunId)), Optional::isPresent)
			.orElseThrow()
			.id();

		var wokenAt = Instant.now();
		this.scenario.decide(claimId, ReviewOutcome.READY, Set.of());
		Awaitility.await().atMost(IntakeScenario.WAIT).until(() -> this.scenario.claim(claimId).status.equals("In Process"));

		var other = createClaim(OTHER_SENDER, COMPLETE_DETAILS);
		var otherConversationId = this.scenario.intake(other.claim().id).orElseThrow().intakeConversationId;
		this.scenario.awaitWaitingRun(other.claim().id);

		var notAClaimMarker = marker();
		classify(notAClaimMarker, EmailType.NOT_A_CLAIM);
		this.scenario.awaitFiled(this.scenario.submit(Mail.from(NOT_A_CLAIM_SENDER).subject("Newsletter").body("Unsubscribe me. " + notAClaimMarker)));

		var claimRuns = this.startedRuns.of(SENDER);
		var otherRun = this.startedRuns.of(OTHER_SENDER).getFirst();
		var notAClaimRun = this.startedRuns.of(NOT_A_CLAIM_SENDER).getFirst();
		awaitEnded(List.of(firstRunId, reviewRunId, notAClaimRun.runId()));

		awaitRunSpans(otherRun.runId(), Set.of("claims-mailbox-agent", "email-router", "claim-extraction-workflow"),
			Set.of("imap find", "imap move", "smtp send"), false);
		var runs = this.startedRuns.all();
		var census = SpanCensus.of(this.spans.all(), runs, Set.of());
		var reviewTrace = this.spans.ofRun(reviewRunId).orElseThrow().getTraceId();
		var afterWakeUp = census.filter(span -> span.getTraceId().equals(reviewTrace) && (span.getStartEpochNanos() >= epochNanos(wokenAt)));
		Log.infof("%s", census.render("a claim's first email, follow-up and decision; another claim; a not-a-claim email"));
		Log.infof("%s", afterWakeUp.render("the review run after it woke up for the decision"));

		assertThat(claimRuns).extracting(StartedRun::runId).containsExactly(firstRunId, reviewRunId);
		assertThat(claimRuns).extracting(StartedRun::conversationId).containsOnly(conversationId);
		assertThat(otherRun.conversationId()).isEqualTo(otherConversationId).isNotEqualTo(conversationId);
		assertThat(notAClaimRun.conversationId()).isNotIn(conversationId, otherConversationId);
		assertThat(this.scenario.claimCount(NOT_A_CLAIM_SENDER)).as("a not-a-claim email creates no claim").isZero();
		assertThat(this.scenario.intakeStateCount()).as("only the two claims have a persisted id").isEqualTo(2);

		assertCarriesOnlyOwnIds(runs, census);
		var canceledSpan = this.spans.ofRun(firstRunId).orElseThrow();
		assertThat(TestSpans.conversationIdOf(canceledSpan))
			.as("a canceled run's workflow.execute span is exported and carries the id")
			.contains(conversationId);
		assertThat(canceledSpan.getAttributes().get(GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME))
			.as("a canceled run's own workflow isn't an agent")
			.isNull();
		assertThat(this.spans.byConversation(conversationId).stream().filter(span -> span.getParentSpanContext().isValid()).toList())
			.as("the claim's id is only on its two runs' spans")
			.isNotEmpty()
			.allSatisfy(span -> assertThat(span.getTraceId()).isIn(this.spans.ofRun(firstRunId).orElseThrow().getTraceId(), reviewTrace));
		assertThat(afterWakeUp.count(Kind.FLOW_RUN)).as("the decision steps' spans after the wake-up").isPositive();
		assertThat(afterWakeUp.count(Kind.JDBC_PARENTED)).as("the decision's statements after the wake-up").isPositive();
		assertThat(afterWakeUp.entries()).allSatisfy(entry -> assertThat(TestSpans.conversationIdOf(entry.span())).contains(conversationId));
	}

	@Test
	void aFaultedRunRecordsItsExceptionAndCarriesItsId() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		var claimId = created.claim().id;
		var run = this.scenario.awaitWaitingRun(claimId);
		var conversationId = this.scenario.intake(claimId).orElseThrow().intakeConversationId;

		doThrow(new IllegalStateException("Simulated step failure"))
			.doCallRealMethod()
			.when(this.decisionSteps).markInProcess(any(), any(), any());

		this.scenario.decide(claimId, ReviewOutcome.READY, Set.of());

		Awaitility.await().atMost(IntakeScenario.WAIT).until(() -> run.status() == WorkflowStatus.FAULTED);

		var faultedSpan = Awaitility.await()
			.atMost(IntakeScenario.WAIT)
			.until(() -> this.spans.ofRun(run.id()), Optional::isPresent)
			.orElseThrow();
		assertThat(TestSpans.conversationIdOf(faultedSpan)).contains(conversationId);
		assertThat(faultedSpan.getAttributes().get(GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME)).as("the run's own workflow isn't an agent").isNull();

		assertRecordsFailure(faultedSpan);
		assertFaultPathCarriesOnlyOwnId(this.startedRuns.of(SENDER).getFirst());
	}

	@Test
	void aFailedNestedAgentEndsAndRecordsFailureAtEveryGeneratedWorkflowLevel() {
		var marker = marker();
		classify(marker, EmailType.NEW_CLAIM);
		// Fail inside extraction, after routing, so all three generated workflow levels must unwind.
		this.stubs.failWhenAll(List.of(DETAILS, marker));
		var runsBefore = this.scenario.agenticRunIds();

		var messageId = this.scenario.submit(Mail.from(SENDER).subject("Car accident").body("My claim. " + marker));
		this.scenario.awaitFailed(messageId);

		Awaitility.await()
			.atMost(IntakeScenario.WAIT)
			.untilAsserted(() -> {
				assertThat(this.startedRuns.of(SENDER)).hasSize(1);
				var run = this.startedRuns.of(SENDER).getFirst();
				var workflow = this.spans.ofRun(run.runId()).orElseThrow();
				assertRecordsFailure(workflow);
				var traceSpans = this.spans.ofTrace(workflow.getTraceId());
				var generatedNames = Set.of("claims-mailbox-agent", "email-router", "claim-extraction-workflow");
				var generated = traceSpans.stream()
					.filter(span -> span.getName().startsWith("workflow.execute"))
					.filter(span -> generatedNames.contains(span.getAttributes().get(TestSpans.WORKFLOW_NAME)))
					.toList();

				// Exact names prevent a missing/unended level from disappearing behind an allSatisfy assertion.
				assertThat(generated)
					.extracting(span -> span.getAttributes().get(TestSpans.WORKFLOW_NAME))
					.containsExactlyInAnyOrderElementsOf(generatedNames);
				assertThat(generated).allSatisfy(span -> {
					assertRecordsFailure(span);
					assertThat(span.getParentSpanContext().isValid()).as("%s has a parent", span.getName()).isTrue();
				});
				// workflow.create ends at startup: correlate its instance independently of the execute span's export.
				assertThat(traceSpans)
					.filteredOn(span -> span.getName().startsWith("workflow.create")
						&& generatedNames.contains(span.getAttributes().get(TestSpans.WORKFLOW_NAME)))
					.extracting(span -> span.getAttributes().get(TestSpans.INSTANCE_ID))
					.containsExactlyInAnyOrderElementsOf(generated.stream()
						.map(span -> span.getAttributes().get(TestSpans.INSTANCE_ID))
						.toList());
				assertThat(this.scenario.agenticRunIds()).as("failed generated runs leave no persisted instances").isSubsetOf(runsBefore);
				assertFaultPathCarriesOnlyOwnId(run);
			});
		assertThat(this.stubs.lastMessage(this.stubs.requestFor(DETAILS)))
			.as("the nested incident-details agent actually called the model for this email")
			.contains(marker);
	}

	private static void assertRecordsFailure(SpanData span) {
		assertThat(span.hasEnded()).as("%s ended", span.getName()).isTrue();
		assertThat(span.getStatus().getStatusCode()).as("%s reports failure", span.getName()).isEqualTo(StatusCode.ERROR);
		assertThat(span.getEvents())
			.as("%s records its own exception, not just a child's error", span.getName())
			.filteredOn(event -> event.getName().equals("exception"))
			.isNotEmpty();
	}

	private void assertFaultPathCarriesOnlyOwnId(StartedRun run) {
		flushSpans();
		var workflow = this.spans.ofRun(run.runId()).orElseThrow();
		assertThat(this.spans.ofTrace(workflow.getTraceId()))
			.as("every exported span in the faulting run's trace carries its input's id")
			.isNotEmpty()
			.allSatisfy(span -> assertThat(TestSpans.conversationIdOf(span)).as(span.getName()).contains(run.conversationId()));
		// Inspect all captured spans, not only those already carrying the expected id or trace.
		// A failed review can start a replacement run with the same id; include it rather than treating recovery as a leak.
		var runs = this.startedRuns.all();
		var census = SpanCensus.of(this.spans.all(), runs, Set.of());
		assertThat(census.runsNotInOneTrace(runs)).isEmpty();
		assertThat(census.wrongInRunTraces()).extracting(SpanCensus.Entry::describe).isEmpty();
		assertThat(census.idsOutsideRunTraces()).extracting(SpanCensus.Entry::describe).isEmpty();
		assertThat(census.lostOutsideRunTraces()).extracting(SpanCensus.Entry::describe).isEmpty();
		assertThat(census.mixedTraces()).isEmpty();
	}

	/**
	 * Delivers one new-claim email from each of {@link #CONCURRENT_RUNS} senders, then submits them together, so their runs
	 * overlap. Waits until every run has ended and its spans are exported.
	 */
	private List<StartedRun> runConcurrently(String batch) {
		var emails = IntStream.range(0, CONCURRENT_RUNS)
			.mapToObj(i -> {
				var marker = marker();
				this.stubs.answerWhenAll(List.of(CLASSIFY, marker), EmailType.NEW_CLAIM.name(), MODEL_DELAY_MS);
				this.stubs.answerWhenAll(List.of(DETAILS, marker), INCOMPLETE_DETAILS, MODEL_DELAY_MS);
				return this.scenario.deliverToProcessing(Mail.from("claimant.%s.%d@example.com".formatted(batch, i)).name(SENDER_NAME).subject("Car accident").body("My claim. " + marker));
			})
			.toList();
		var before = this.startedRuns.all().size();

		emails.forEach(this.scenario::submit);
		emails.forEach(email -> this.scenario.awaitFiled(email.messageId().orElseThrow()));

		var runs = Awaitility.await()
			.atMost(IntakeScenario.WAIT)
			.until(() -> this.startedRuns.all(), all -> all.size() == (before + CONCURRENT_RUNS))
			.subList(before, before + CONCURRENT_RUNS);
		awaitEnded(runs.stream().map(StartedRun::runId).toList());

		var executions = runs.stream().map(run -> this.spans.ofRun(run.runId()).orElseThrow()).toList();
		var lastStart = executions.stream().mapToLong(SpanData::getStartEpochNanos).max().orElseThrow();
		var firstEnd = executions.stream().mapToLong(SpanData::getEndEpochNanos).min().orElseThrow();
		Log.infof("Batch %s: the last run started %d ms before the first one ended", batch, (firstEnd - lastStart) / 1_000_000);
		assertThat(lastStart).as("all %d runs are in flight at once", CONCURRENT_RUNS).isLessThan(firstEnd);

		return runs;
	}

	/**
	 * Awaits ended workflows and the generated workflow/mail spans expected for each email's route, then flushes.
	 */
	private void awaitEnded(List<String> runIds) {
		runIds.forEach(runId -> {
			var run = this.startedRuns.all().stream()
				.filter(started -> started.runId().equals(runId))
				.findFirst()
				.orElseThrow();
			var generated = NOT_A_CLAIM_SENDER.equals(run.input().sender())
				? Set.of("claims-mailbox-agent", "email-router")
				: Set.of("claims-mailbox-agent", "email-router", "claim-extraction-workflow");
			awaitRunSpans(runId, generated, Set.of("imap find", "imap move", "smtp send"));
		});
		flushSpans();
	}

	private static void assertCarriesOnlyOwnIds(List<StartedRun> runs, SpanCensus census) {
		assertThat(census.runsNotInOneTrace(runs)).as("every run's Flow spans are in one trace").isEmpty();
		assertThat(census.wrongInRunTraces()).as("spans in a run's trace without exactly its id").extracting(SpanCensus.Entry::describe).isEmpty();
		assertThat(census.idsOutsideRunTraces()).as("ids on spans outside their run's trace").extracting(SpanCensus.Entry::describe).isEmpty();
		assertThat(census.lostOutsideRunTraces()).as("Flow, AI and parented JDBC spans outside every run's trace").extracting(SpanCensus.Entry::describe).isEmpty();
		assertThat(census.mixedTraces()).as("traces whose spans carry more than one id").isEmpty();
		assertThat(census.count(Kind.FLOW_SUB_WORKFLOW)).as("the agent sub-workflows' spans were seen").isPositive();
		assertThat(census.count(Kind.AI_UNDER_SUB_WORKFLOW)).as("the AI calls under the sub-workflows were seen").isPositive();
		assertThat(census.count(Kind.JDBC_PARENTED)).as("parented JDBC spans were seen").isPositive();
		assertThat(census.count(Kind.MAIL)).as("mail spans were seen").isPositive();
		var spans = census.entries().stream().map(SpanCensus.Entry::span).toList();
		assertThat(spans).filteredOn(span -> span.getName().startsWith("workflow.execute"))
			.as("the run's own workflow isn't an agent, the nested agentic ones are")
			.isNotEmpty()
			.allSatisfy(span -> assertThat(span.getAttributes().get(GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME))
				.isEqualTo(ClaimIntakeFlow.NAME.equals(span.getAttributes().get(TestSpans.WORKFLOW_NAME)) ? null :
                   GenAiOperationNameIncubatingValues.INVOKE_AGENT));
		assertThat(spans).filteredOn(span -> span.getName().startsWith("task.execute"))
			.as("task.execute spans are chains")
			.isNotEmpty()
			.allSatisfy(span -> {
				assertThat(span.getAttributes().get(LangfuseFlowSpanTypingProcessor.LANGFUSE_OBSERVATION_TYPE))
					.isEqualTo(LangfuseFlowSpanTypingProcessor.CHAIN);
				assertThat(span.getAttributes().get(GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME)).isNull();
			});
	}

	private static Set<String> ids(List<StartedRun> runs) {
		return runs.stream().map(StartedRun::conversationId).collect(Collectors.toSet());
	}

	private static long epochNanos(Instant instant) {
		return ChronoUnit.NANOS.between(Instant.EPOCH, instant);
	}
}
