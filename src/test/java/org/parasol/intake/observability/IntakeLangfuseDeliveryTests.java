package org.parasol.intake.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.parasol.intake.IntakeFlowTestBase;
import org.parasol.intake.IntakeScenario;
import org.parasol.intake.IntakeScenario.Mail;
import org.parasol.testing.LangfuseExportTestProfile;
import org.parasol.intake.StartedRuns;
import org.parasol.intake.TestSpans;
import org.parasol.intake.review.ReviewOutcome;

import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import com.langfuse.api.model.ObservationV2;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.quarkiverse.langfuse.api.LangfuseOperations;
import io.quarkiverse.langfuse.api.ObservationFilter;

/**
 * Verifies real Langfuse Dev Services ingestion, not just locally stamped attributes: generated workflows and tasks
 * survive AI_ONLY filtering across exports and multiple intake traces group into one claim session.
 */
@QuarkusTest
@TestProfile(LangfuseExportTestProfile.class)
class IntakeLangfuseDeliveryTests extends IntakeFlowTestBase {
	private static final Set<String> GENERATED = Set.of("claims-mailbox-agent", "email-router", "claim-extraction-workflow");

	@Inject
	LangfuseOperations langfuse;

	@Inject
	StartedRuns startedRuns;

	@BeforeEach
	void resetRuns() {
		this.startedRuns.reset();
	}

	@Test
	void firstEmailFollowUpAndDecisionReachOneLangfuseSessionAcrossExports() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		var claimId = created.claim().id;
		var firstRunId = this.scenario.awaitWaitingRun(claimId).id();
		var conversationId = this.scenario.intake(claimId).orElseThrow().intakeConversationId;
		awaitRunSpans(firstRunId, GENERATED, Set.of("imap find", "imap move", "smtp send"), false);
		// The first workflow is still waiting, so force a separate export before its cancellation and the decision.
		assertDelivered(conversationId, expectedTypedSpans(firstRunId));

		var marker = marker();
		stubFollowUp(marker, COMPLETE_DETAILS.replace("Elm Street", "Oak Avenue"));
		var followUpId = this.scenario.submit(Mail.from(SENDER).inReplyTo(created.messageId()).body("Actually Oak Avenue. " + marker));
		this.scenario.awaitFiled(followUpId);
		var reviewRun = Awaitility.await()
			.atMost(IntakeScenario.WAIT)
			.until(() -> this.scenario.waitingRun(claimId).filter(run -> !run.id().equals(firstRunId)), Optional::isPresent)
			.orElseThrow();
		this.scenario.decide(claimId, ReviewOutcome.READY, Set.of());
		awaitRunSpans(firstRunId, GENERATED, Set.of("imap find", "imap move", "smtp send"));
		awaitRunSpans(reviewRun.id(), GENERATED, Set.of("imap find", "imap move", "smtp send"));
		assertThat(this.scenario.claim(claimId).status).isEqualTo("In Process");
		assertThat(this.startedRuns.of(SENDER))
			.extracting(StartedRuns.StartedRun::conversationId)
			.containsExactly(conversationId, conversationId);

		var expected = Stream.concat(expectedTypedSpans(firstRunId).stream(), expectedTypedSpans(reviewRun.id()).stream()).toList();
		assertThat(expected).extracting(SpanData::getTraceId).containsOnly(
			this.spans.ofRun(firstRunId).orElseThrow().getTraceId(), this.spans.ofRun(reviewRun.id()).orElseThrow().getTraceId());
		assertThat(expected.stream().map(SpanData::getTraceId).collect(Collectors.toSet())).hasSize(2);
		assertThat(expected)
			.filteredOn(span -> "applyReady".equals(span.getAttributes().get(AttributeKey.stringKey("flow.task.name"))))
			.as("the review decision's task is included in the backend delivery assertions")
			.isNotEmpty();
		assertDelivered(conversationId, expected);
	}

	private List<SpanData> expectedTypedSpans(String runId) {
		var traceId = this.spans.all().stream()
			.filter(span -> runId.equals(span.getAttributes().get(TestSpans.INSTANCE_ID)))
			.findFirst()
			.orElseThrow()
			.getTraceId();
		var trace = this.spans.ofTrace(traceId);
		var generated = trace.stream()
			.filter(span -> span.getName().startsWith("workflow.execute"))
			.filter(span -> GENERATED.contains(span.getAttributes().get(TestSpans.WORKFLOW_NAME)))
			.toList();
		assertThat(generated).extracting(span -> span.getAttributes().get(TestSpans.WORKFLOW_NAME))
			.containsExactlyInAnyOrderElementsOf(GENERATED);
		var tasks = trace.stream()
			.filter(span -> span.getName().startsWith("task.execute"))
			.toList();
		assertThat(tasks).isNotEmpty();
		return Stream.concat(generated.stream(), tasks.stream()).toList();
	}

	private void assertDelivered(String conversationId, List<SpanData> expected) {
		var filter = ObservationFilter.builder()
			.sessionId(conversationId)
			.fields("core,basic,metadata")
			.build();
		Awaitility.await()
			.atMost(Duration.ofSeconds(90))
			.pollInterval(Duration.ofSeconds(1))
			.untilAsserted(() -> {
				flushSpans();
				var observations = this.langfuse.observations().matching(filter).findAll();
				var byId = observations.stream().collect(Collectors.toMap(ObservationV2::getId, Function.identity()));
				assertThat(observations).isNotEmpty();
				assertThat(expected).allSatisfy(span -> {
					assertThat(byId).as("ingested %s %s", span.getName(), span.getSpanId()).containsKey(span.getSpanId());
					var observation = byId.get(span.getSpanId());
					assertThat(observation.getSessionId()).isEqualTo(conversationId);
					assertThat(observation.getTraceId()).isEqualTo(span.getTraceId());
					assertThat(observation.getName()).isEqualTo(span.getName());
					assertThat(observation.getType()).isEqualTo(span.getName().startsWith("workflow.execute") ? "AGENT" : "CHAIN");
				});
			});
		Log.infof("Langfuse delivery verified: session %s, %d expected workflow/task observations", conversationId, expected.size());
	}
}
