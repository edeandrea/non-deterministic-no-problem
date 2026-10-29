package org.parasol.intake;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.parasol.intake.IntakeScenario.Mail;
import org.parasol.intake.model.EmailType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Shared setup of the intake workflow tests. Each subclass carries the annotations ({@code @QuarkusTest},
 * {@code @TestProfile(IntakeTestProfile.class)}, {@code @ConnectWireMock}) so every class shares one app boot.
 * <p>
 *   Stubs match on a prompt phrase (which agent) and a marker in the email (which test), so each email is stubbed on its own.
 * </p>
 */
public abstract class IntakeFlowTestBase {
	protected static final String SENDER = "jane.doe@example.com";
	protected static final String SENDER_NAME = "Jane Doe";
	protected static final String CLASSIFY = "Classify the following email";
	protected static final String SUMMARY = "Summarize the following claim correspondence";
	protected static final String SENTIMENT = "Assess the claimant's sentiment";
	protected static final String DETAILS = "Extract the incident details from the following correspondence";
	protected static final String FOLLOW_UP = "Answer the following email about the customer's claim";
	protected static final String RESOLVER = "Decide which of the customer's pending claims";
	protected static final String STATUS_ANSWER = "Hello, your claim is being worked on. Sincerely, the claims department";
	protected static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3 };

	protected static final String COMPLETE_DETAILS = details("Car hit a post.", "2026-10-07", "Elm Street", "SINGLE_VEHICLE", null, List.of());

	@Inject
	protected IntakeScenario scenario;

	@Inject
	protected ObjectMapper objectMapper;

	@Inject
	protected TestSpans spans;

	@Inject
	protected SimpleMeterRegistry meters;

	// Quarkus registers this as a synthetic bean in OpenTelemetryProcessor, which IDE CDI inspection cannot see.
	@Inject
	protected OpenTelemetry telemetry;

	protected WireMock wiremock;
	protected ChatStubs stubs;

	/**
	 * Exports already-ended spans with a bounded SDK barrier; this does not end active workflows or background work.
	 */
	protected void flushSpans() {
		assertThat(this.telemetry).isInstanceOf(OpenTelemetrySdk.class);
		var result = ((OpenTelemetrySdk) this.telemetry).getSdkTracerProvider().forceFlush();
		result.join(10, TimeUnit.SECONDS);
		assertThat(result.isSuccess()).as("completed spans exported within 10 seconds").isTrue();
	}

	/**
	 * Awaits a run's ended span and its expected completed sub-workflows and mail operations before a final export barrier.
	 *
	 * @param runId The intake run
	 * @param generatedNames The exact generated workflow names expected for this route
	 * @param mailOperations The mail span names required for this route
	 */
	protected void awaitRunSpans(String runId, Set<String> generatedNames, Set<String> mailOperations) {
		awaitRunSpans(runId, generatedNames, mailOperations, true);
	}

	/**
	 * Checks completed work within either an ended or a still-waiting intake run.
	 *
	 * @param runId The intake run
	 * @param generatedNames The expected generated workflows
	 * @param mailOperations The required completed mail operations
	 * @param ended Whether the intake workflow itself must have ended
	 */
	protected void awaitRunSpans(String runId, Set<String> generatedNames, Set<String> mailOperations, boolean ended) {
		Awaitility.await()
			.atMost(IntakeScenario.WAIT)
			.untilAsserted(() -> {
				flushSpans();
				var anchor = this.spans.all().stream()
					.filter(span -> runId.equals(span.getAttributes().get(TestSpans.INSTANCE_ID)))
					.filter(span -> span.getName().startsWith(ended ? "workflow.execute" : "workflow.create"))
					.findFirst();
				assertThat(anchor).as("run %s has its %s anchor", runId, ended ? "ended" : "created").isPresent();
				var trace = this.spans.ofTrace(anchor.orElseThrow().getTraceId());
				assertThat(trace.stream()
					.filter(span -> span.getName().startsWith("workflow.execute"))
					.filter(span -> !runId.equals(span.getAttributes().get(TestSpans.INSTANCE_ID)))
					.map(span -> span.getAttributes().get(TestSpans.WORKFLOW_NAME))
					.toList())
					.containsExactlyInAnyOrderElementsOf(generatedNames);
				assertThat(trace).extracting(SpanData::getName).containsAll(mailOperations);
				assertThat(trace)
					.filteredOn(span -> span.getName().startsWith("langchain4j.aiservices."))
					.as("run %s exported AI service spans", runId)
					.isNotEmpty();
				assertThat(trace)
					.filteredOn(span -> span.getParentSpanContext().isValid()
						&& ((span.getAttributes().get(AttributeKey.stringKey("db.system")) != null)
							|| (span.getAttributes().get(AttributeKey.stringKey("db.system.name")) != null)))
					.as("run %s exported parented JDBC spans", runId)
					.isNotEmpty();
				// Every created generated instance must have an ended execution, not merely one span from its family.
				var created = trace.stream()
					.filter(span -> span.getName().startsWith("workflow.create"))
					.filter(span -> generatedNames.contains(span.getAttributes().get(TestSpans.WORKFLOW_NAME)))
					.map(span -> span.getAttributes().get(TestSpans.INSTANCE_ID))
					.toList();
				var executed = trace.stream()
					.filter(span -> span.getName().startsWith("workflow.execute"))
					.filter(span -> generatedNames.contains(span.getAttributes().get(TestSpans.WORKFLOW_NAME)))
					.map(span -> span.getAttributes().get(TestSpans.INSTANCE_ID))
					.toList();
				assertThat(created).containsExactlyInAnyOrderElementsOf(executed);
			});
		flushSpans();
	}

	/**
	 * What the intake's meters say now. The registry outlives a test and isn't reset, so a test takes this before and after and
	 * compares, never an absolute value.
	 *
	 * @param name The meter's name
	 * @param tags The tags to match, as alternating keys and values
	 * @return The sum of every matching counter
	 */
	protected double counted(String name, String... tags) {
		return this.meters.find(name)
			.tags(tags)
			.counters()
			.stream()
			.mapToDouble(Counter::count)
			.sum();
	}

	protected void awaitCounted(double expected, String name, String... tags) {
		Awaitility.await()
			.atMost(IntakeScenario.WAIT)
			.untilAsserted(() -> assertThat(counted(name, tags)).as("%s %s".formatted(name, List.of(tags))).isEqualTo(expected));
	}

	@BeforeEach
	protected void setUpIntake() {
		this.stubs = new ChatStubs(this.wiremock, this.objectMapper);
		this.stubs.reset();
		this.scenario.reset();
		flushSpans();
		this.spans.startCapture();
		this.stubs.answer(SUMMARY, "A car hit a post on Elm Street.");
		this.stubs.answer(SENTIMENT, "Calm.");
		this.stubs.callTool(FOLLOW_UP, "findClaimStatus", "{}");
		this.stubs.answer("has the status:", STATUS_ANSWER);
	}

	@AfterEach
	protected void cleanUpIntake() {
		try {
			this.scenario.cleanUp();
			flushSpans();
		}
		finally {
			this.spans.stopCapture();
		}
	}

	protected static String details(String description, String date, String location, String category, String policyNumber, List<String> answered) {
		return """
			{"description":%s,"incidentDate":%s,"incidentTime":null,"location":%s,"category":%s,"policyNumber":%s,"answeredItems":%s}"""
			.formatted(quote(description), quote(date), quote(location), quote(category), quote(policyNumber),
				answered.stream().map(IntakeFlowTestBase::quote).toList());
	}

	protected static String quote(String value) {
		return (value == null) ? "null" : "\"%s\"".formatted(value);
	}

	protected void classify(String marker, EmailType type) {
		this.stubs.answerWhenAll(List.of(CLASSIFY, marker), type.name());
	}

	protected void extract(String marker, String detailsJson) {
		this.stubs.answerWhenAll(List.of(DETAILS, marker), detailsJson);
	}

	/**
	 * Creates a claim by a customer's first email and waits until it's filed. Returns the claim and the email's Message-ID.
	 */
	protected Created createClaim(String address, String detailsJson) {
		var marker = marker();
		stubNewClaim(marker, detailsJson);

		var id = this.scenario.submit(Mail.from(address).name(SENDER_NAME).subject("Car accident").body("First email. " + marker));
		this.scenario.awaitFiled(id);

		return new Created(this.scenario.awaitClaimOf(address), id);
	}

	protected record Created(org.parasol.claim.model.Claim claim, String messageId) {
	}

	protected static String marker() {
		return "[[%s]]".formatted(java.util.UUID.randomUUID());
	}

	protected void stubNewClaim(String marker, String detailsJson) {
		classify(marker, EmailType.NEW_CLAIM);
		extract(marker, detailsJson);
	}

	protected void stubFollowUp(String marker, String detailsJson) {
		classify(marker, EmailType.CLAIM_FOLLOW_UP);
		extract(marker, detailsJson);
	}
}
