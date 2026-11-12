package org.parasol.intake.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.parasol.intake.IntakeFlowTestBase;
import org.parasol.intake.IntakeScenario;
import org.parasol.intake.IntakeScenario.Mail;
import org.parasol.intake.StartedRuns;
import org.parasol.intake.TestSpans;
import org.parasol.intake.model.EmailType;

import com.fasterxml.jackson.core.type.TypeReference;

import dev.langchain4j.service.decision.Choice;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.quarkus.test.junit.QuarkusTest;

/**
 * #221 spike: a decision agent inside a real {@code ClaimIntakeFlow} run, its spans, and how a decision answer type
 * survives the Quarkus {@code ObjectMapper}. Writes its evidence to {@code target/spike-int-*.txt}.
 */
@QuarkusTest
class DecisionSpikeIntegrationTests extends IntakeFlowTestBase {
	@Inject
	StartedRuns startedRuns;

	@BeforeEach
	void resetRuns() {
		this.startedRuns.reset();
	}

	@Test
	void theDecisionCallIsInTheRunsTrace() throws Exception {
		var marker = marker();
		stubNewClaim(marker, details(true, "2026-10-07", null, "SINGLE_VEHICLE", null, java.util.List.of()));

		var messageId = this.scenario.submit(Mail.from(SENDER).name(SENDER_NAME).subject("Car accident").body("My claim. " + marker));
		this.scenario.awaitFiled(messageId);
		var run = await()
			.atMost(IntakeScenario.WAIT)
			.until(() -> this.startedRuns.of(SENDER), runs -> !runs.isEmpty())
			.getFirst();
		this.scenario.awaitReplies(SENDER, 1);
		await().atMost(IntakeScenario.WAIT).untilAsserted(() -> {
			flushSpans();
			assertThat(this.spans.all()).anyMatch(span -> run.runId().equals(span.getAttributes().get(TestSpans.INSTANCE_ID)) && span.getName().startsWith("workflow.execute"));
		});
		Thread.sleep(2000);
		flushSpans();

		var trace = this.spans.ofRun(run.runId()).orElseThrow().getTraceId();
		var traceSpans = this.spans.ofTrace(trace).stream().sorted(Comparator.comparingLong(SpanData::getStartEpochNanos)).toList();
		Files.writeString(Path.of("target/spike-int-spans.txt"), traceSpans.stream()
			.map(span -> "%s [%s] parent=%s conversation=%s %s".formatted(span.getName(), span.getKind(), span.getParentSpanId(),
				TestSpans.conversationIdOf(span).orElse("-"), span.getAttributes().asMap()))
			.collect(Collectors.joining("\n")));

		var requests = this.wiremock.getServeEvents().stream().map(event -> event.getRequest().getUrl()).toList();
		Files.writeString(Path.of("target/spike-int-requests.txt"), String.join("\n", requests));
		assertThat(requests).as("the classifier went to System One").contains("/v1/systemone");

		assertThat(traceSpans)
			.filteredOn(span -> span.getName().startsWith("decide "))
			.as("one decision span, from DecisionModelSpanListener, in the run's trace and conversation")
			.hasSize(1)
			.allSatisfy(span -> assertThat(TestSpans.conversationIdOf(span)).contains(run.conversationId()));
	}

	@Test
	void decisionAnswerTypesThroughTheQuarkusObjectMapper() throws Exception {
		var results = new LinkedHashMap<String, String>();
		results.put("EmailType", roundTrip(EmailType.NEW_CLAIM, new TypeReference<EmailType>() {}));
		var choice = Choice.<EmailType>builder().value(EmailType.NEW_CLAIM).probabilities(Map.of(EmailType.NEW_CLAIM, 0.9, EmailType.NOT_A_CLAIM, 0.1)).confidence(0.8).build();
		results.put("Choice<EmailType>", roundTrip(choice, new TypeReference<Choice<EmailType>>() {}));
		results.put("IncidentAssessment", roundTrip(
			new org.parasol.intake.agent.extraction.IncidentAssessment(true, false, true, org.parasol.intake.agent.extraction.IncidentCategory.THEFT),
			new TypeReference<org.parasol.intake.agent.extraction.IncidentAssessment>() {}));
		Files.writeString(Path.of("target/spike-int-jackson.txt"), results.toString());
		assertThat(results.get("EmailType")).startsWith("ok");
		assertThat(results.get("IncidentAssessment")).startsWith("ok");
	}

	private <T> String roundTrip(T value, TypeReference<T> type) {
		try {
			var json = this.objectMapper.writeValueAsString(value);
			var back = this.objectMapper.readValue(json, type);
			return (value.equals(back) ? "ok " : "DIFFERS ") + json;
		}
		catch (Exception e) {
			return "FAILS " + e.getClass().getSimpleName() + ": " + e.getMessage().lines().findFirst().orElse("");
		}
	}
}
