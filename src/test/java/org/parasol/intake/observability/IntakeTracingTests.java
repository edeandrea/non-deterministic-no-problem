package org.parasol.intake.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import jakarta.inject.Inject;

import org.awaitility.Awaitility;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logmanager.ExtLogRecord;
import org.jboss.logmanager.formatters.PatternFormatter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.parasol.intake.IntakeFlowTestBase;
import org.parasol.intake.IntakeScenario;
import org.parasol.intake.IntakeScenario.Mail;
import org.parasol.intake.StartedRuns;
import org.parasol.intake.TestSpans;
import org.parasol.intake.config.IntakeConfig;
import org.parasol.intake.review.ReviewOutcome;
import org.parasol.intake.workflow.ClaimIntakeFlow;
import org.parasol.intake.workflow.review.ReviewDecisionSteps;
import org.parasol.testing.StubbedModelsTestProfile;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;

/**
 * What the intake's mail work and logs show of a run: IMAP and SMTP {@code CLIENT} spans that carry the run's conversation id
 * and name the server and folder but never a person or an email's content, and log lines from a step that carry the run's
 * trace id in the MDC.
 */
@QuarkusTest
@TestProfile(StubbedModelsTestProfile.class)
class IntakeTracingTests extends IntakeFlowTestBase {
	private static final String NO_LOCATION = details(true, "2026-10-07", null, "SINGLE_VEHICLE", null, List.of());
	private static final AttributeKey<String> MESSAGING_SYSTEM = AttributeKey.stringKey("messaging.system");
	private static final AttributeKey<String> SERVER_ADDRESS = AttributeKey.stringKey("server.address");
	private static final AttributeKey<Long> SERVER_PORT = AttributeKey.longKey("server.port");
	private static final AttributeKey<String> DESTINATION = AttributeKey.stringKey("messaging.destination.name");
	private static final AttributeKey<String> REPLY_TEMPLATE = AttributeKey.stringKey("claim.intake.reply.template");

	@Inject
	StartedRuns startedRuns;

	@Inject
	IntakeConfig config;

	@ConfigProperty(name = "quarkus.mailer.host")
	String smtpHost;

	@ConfigProperty(name = "quarkus.mailer.port")
	int smtpPort;

	@InjectSpy
	ReviewDecisionSteps decisionSteps;

	private final CapturedLogs logs = new CapturedLogs();

	@BeforeEach
	void capture() {
		this.startedRuns.reset();
		java.util.logging.Logger.getLogger("").addHandler(this.logs);
	}

	@AfterEach
	void release() {
		java.util.logging.Logger.getLogger("").removeHandler(this.logs);
	}

	@Test
	void mailSpansCarryTheRunsIdAndNamePeopleNowhere() {
		var marker = marker();
		stubNewClaim(marker, NO_LOCATION);

		var messageId = this.scenario.submit(Mail.from(SENDER).name(SENDER_NAME).subject("Car accident").body("My claim. " + marker));
		this.scenario.awaitFiled(messageId);
		this.scenario.awaitReplies(SENDER, 1);

		var run = Awaitility.await().atMost(IntakeScenario.WAIT).until(() -> this.startedRuns.of(SENDER), runs -> !runs.isEmpty()).getFirst();
		awaitRunSpans(run.runId(), Set.of("claims-mailbox-agent", "email-router", "claim-extraction-workflow"),
			Set.of("imap find", "imap move", "smtp send"));

		var runTrace = this.spans.ofRun(run.runId()).orElseThrow().getTraceId();
		var mailSpans = this.spans.ofTrace(runTrace).stream()
			.filter(span -> span.getName().startsWith("imap ") || span.getName().equals("smtp send"))
			.toList();

		// Chosen by the run's trace, not by its id, so a mail span that lost the id is a failure here rather than missing from the list
		assertThat(mailSpans)
			.as("mail spans in the run's trace")
			.isNotEmpty()
			.allSatisfy(span -> {
				assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
				assertThat(span.getParentSpanContext().isValid()).isTrue();
				assertThat(TestSpans.conversationIdOf(span)).as("carries the run's conversation id").contains(run.conversationId());
			});

		assertThat(mailSpans)
			.filteredOn(span -> span.getName().equals("imap find"))
			.as("the email is read from the processing folder")
			.isNotEmpty()
			.allSatisfy(this::assertImapSpanOnProcessing);
		assertThat(mailSpans)
			.filteredOn(span -> span.getName().equals("imap move"))
			.as("the email is filed out of the processing folder")
			.isNotEmpty()
			.allSatisfy(this::assertImapSpanOnProcessing);
		assertThat(mailSpans)
			.filteredOn(span -> span.getName().equals("smtp send"))
			.as("the reply is sent")
			.isNotEmpty()
			.allSatisfy(span -> {
				assertThat(span.getAttributes().get(MESSAGING_SYSTEM)).isEqualTo("smtp");
				assertThat(span.getAttributes().get(SERVER_ADDRESS)).isEqualTo(this.smtpHost);
				assertThat(span.getAttributes().get(SERVER_PORT)).isEqualTo(this.smtpPort);
				assertThat(span.getAttributes().get(REPLY_TEMPLATE)).isEqualTo("missingInformation");
			});

		var everything = this.spans.all().stream()
			.filter(span -> span.getName().startsWith("imap ") || span.getName().equals("smtp send"))
			.map(span -> span.getName() + span.getAttributes() + span.getEvents())
			.toList();
		assertThat(everything)
			.as("no address, body or Message-ID on any mail span")
			.isNotEmpty()
			.noneMatch(text -> text.contains(SENDER) || text.contains("claims@parasol.com") || text.contains(marker) || text.contains(messageId));
	}

	@Test
	void aStepsLogLineCarriesTheRunsTraceId() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		var claimId = created.claim().id;
		var reviewRun = this.scenario.awaitWaitingRun(claimId);
		var startedRun = this.startedRuns.of(SENDER)
			.stream()
			.filter(run -> run.runId().equals(reviewRun.id()))
			.findFirst()
			.orElseThrow();

		doThrow(new IllegalStateException("Simulated step failure"))
			.doCallRealMethod()
			.when(this.decisionSteps).applyReady(any(), any());

		this.scenario.decide(claimId, ReviewOutcome.READY, Set.of());
		// Handlers may receive either the format plus parameters or an already-formatted record.
		var formatter = new PatternFormatter("%s");
		var record = Awaitility.await()
			.atMost(IntakeScenario.WAIT)
			.until(() -> this.logs.records().stream()
				.filter(log -> formatter.formatMessage(log).equals(
					"A decision step failed for claim %s: it's back in review and run %s waits for the next decision"
						.formatted(created.claim().claimNumber, reviewRun.id())))
				.findFirst(), Optional::isPresent)
			.orElseThrow();
		var traceId = record.getMdc("traceId");
		var spanId = record.getMdc("spanId");
		assertThat(traceId).as("the step's log line has a trace id").isNotBlank();
		assertThat(spanId).as("the step's log line has a span id").isNotBlank();

		// The log is synchronous, but its task span reaches the exporter only after the task ends and the batch exports.
		// The workflow remains waiting, so correlate with the ended recovery task, not its still-open workflow span.
		Awaitility.await()
			.atMost(IntakeScenario.WAIT)
			.untilAsserted(() -> assertThat(this.spans.all())
				.filteredOn(span -> span.getSpanId().equals(spanId) && span.getTraceId().equals(traceId))
				.singleElement()
				.satisfies(span -> {
					assertThat(span.hasEnded()).isTrue();
					assertThat(span.getName()).startsWith("task.execute");
					assertThat(span.getAttributes().get(TestSpans.INSTANCE_ID)).isEqualTo(startedRun.runId());
					assertThat(span.getAttributes().get(TestSpans.WORKFLOW_NAME)).isEqualTo(ClaimIntakeFlow.NAME);
					assertThat(span.getAttributes().get(AttributeKey.stringKey("flow.task.name"))).isEqualTo("restoreAfterReady");
					assertThat(TestSpans.conversationIdOf(span)).contains(startedRun.conversationId());
				}));
	}

	private void assertImapSpanOnProcessing(SpanData span) {
		assertThat(span.getAttributes().get(MESSAGING_SYSTEM)).isEqualTo("imap");
		assertThat(span.getAttributes().get(SERVER_ADDRESS)).isEqualTo(this.config.imap().host());
		assertThat(span.getAttributes().get(SERVER_PORT)).isEqualTo(this.config.imap().port());
		assertThat(span.getAttributes().get(DESTINATION)).isEqualTo("PROCESSING");
	}

	private static final class CapturedLogs extends Handler {
		private final List<ExtLogRecord> records = new CopyOnWriteArrayList<>();

		@Override
		public void publish(LogRecord logRecord) {
			if (logRecord instanceof ExtLogRecord ext) {
				// The record reads the MDC of whichever thread asks, unless it's copied on the logging thread
				ext.copyMdc();
				this.records.add(ext);
			}
		}

		@Override
		public void flush() {
		}

		@Override
		public void close() {
		}

		List<ExtLogRecord> records() {
			return List.copyOf(this.records);
		}
	}
}
