package org.parasol.intake.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.parasol.intake.ChatStubs;
import org.parasol.intake.IntakeScenario;
import org.parasol.intake.IntakeTestProfile;
import org.parasol.intake.agent.triage.ClaimStatusTools;
import org.parasol.intake.model.EmailType;
import org.parasol.intake.model.IncidentDetails;
import org.parasol.intake.model.MatchedClaim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import dev.langchain4j.guardrails.JsonExtractorOutputGuardrail;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkiverse.wiremock.devservice.ConnectWireMock;

/**
 * A failed root invocation leaves none of the Flow runs quarkus-flow generated for it, and no worker thread blocked in
 * one of them. quarkus-flow 1.2.0 left both behind on a failure (a worker thread parked forever in
 * {@code AgenticFlow.executeAgent}'s join); 1.3.0 fixes it upstream, in
 * <a href="https://github.com/quarkiverse/quarkus-flow/pull/1095">quarkiverse/quarkus-flow#1095</a>. This test is kept
 * unchanged as the regression guard for that fix.
 */
@QuarkusTest
@TestProfile(IntakeTestProfile.class)
@ConnectWireMock
class FailedAgenticSystemTests {
	// Where a generated workflow's task waits for its agent call: FlowPlanner.executeAgent(i).join()
	private static final String AGENT_CALL_CLASS = "io.quarkiverse.flow.langchain4j.workflow.flow.AgenticFlow";
	private static final String AGENT_CALL_METHOD = "executeAgent";
	private static final String SENT_DATE = "2026-10-08";
	private static final String CLASSIFY = "Classify the following email";
	private static final String SUMMARY = "Summarize the following claim correspondence";
	private static final String SENTIMENT = "Assess the claimant's sentiment";
	private static final String DETAILS = "Extract the incident details from the following correspondence";

	@Inject
	ClaimsMailboxAgent agent;

	@Inject
	IntakeScenario scenario;

	@Inject
	ObjectMapper objectMapper;

	WireMock wiremock;

	ChatStubs stubs;

	@BeforeEach
	void setUp() {
		this.stubs = new ChatStubs(this.wiremock, this.objectMapper);
		this.stubs.reset();
	}

	@Test
	void aFailedClassifierLeavesNoFlowRunOfTheRoot() {
		this.stubs.failWhenAll(List.of(CLASSIFY));

		assertFailureLeavesNothingBehind();
	}

	@Test
	void aFailedNestedExtractionLeavesNoFlowRunAtAnyLevel() {
		this.stubs.answer(CLASSIFY, EmailType.NEW_CLAIM.name());
		this.stubs.answer(SUMMARY, "A car hit a post on Elm Street.");
		this.stubs.answer(SENTIMENT, "Calm.");
		this.stubs.answer(DETAILS, "I'm sorry, I can't help with that.");
		this.stubs.answer(JsonExtractorOutputGuardrail.DEFAULT_REPROMPT_PROMPT, "Still not JSON.");

		assertFailureLeavesNothingBehind();
	}

	private void assertFailureLeavesNothingBehind() {
		var runsBefore = this.scenario.agenticRunIds();
		var blockedBefore = threadsBlockedInAnAgentCall();

		assertThatExceptionOfType(RuntimeException.class)
			.isThrownBy(() -> this.agent.process(
				"I hit a post on Elm Street yesterday.",
				MatchedClaim.none(),
				IncidentDetails.empty(),
				Set.of(),
				SENT_DATE,
				ClaimStatusTools.scopedTo(MatchedClaim.none())));

		Awaitility.await()
			.atMost(IntakeScenario.WAIT)
			.untilAsserted(() -> {
				assertThat(this.scenario.agenticRunIds())
					.as("the failed invocation's generated runs ended, and Flow deleted their rows")
					.isSubsetOf(runsBefore);
				assertThat(threadsBlockedInAnAgentCall())
					.as("no worker thread stays blocked waiting for an agent call that will never complete")
					.isSubsetOf(blockedBefore);
			});
	}

	private static Set<Thread> threadsBlockedInAnAgentCall() {
		return Thread.getAllStackTraces()
			.entrySet()
			.stream()
			.filter(thread -> Stream.of(thread.getValue()).anyMatch(FailedAgenticSystemTests::isAgentCall))
			.map(Map.Entry::getKey)
			.collect(Collectors.toUnmodifiableSet());
	}

	private static boolean isAgentCall(StackTraceElement frame) {
		return AGENT_CALL_CLASS.equals(frame.getClassName()) && AGENT_CALL_METHOD.equals(frame.getMethodName());
	}
}
