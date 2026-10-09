package org.parasol.intake.agent.extraction;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.parasol.claim.model.ClaimCategory;
import org.parasol.intake.IntakeAgentsTestProfile;
import org.parasol.intake.MissingItem;
import org.parasol.intake.model.ClaimExtraction;
import org.parasol.intake.model.IncidentDetails;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import dev.langchain4j.guardrails.JsonExtractorOutputGuardrail;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkiverse.wiremock.devservice.ConnectWireMock;

/**
 * The extraction workflow end to end against a stubbed model. Stubs match on the last message only (spike Q6), so a
 * reprompt, which arrives as a new last message, gets its own stub.
 */
@QuarkusTest
@TestProfile(IntakeAgentsTestProfile.class)
@ConnectWireMock
class ClaimExtractionWorkflowTests {
	private static final String SENT_DATE = "2026-10-08";
	private static final String SUMMARY = "The claimant's car hit another vehicle at the Elm Street junction.";
	private static final String SENTIMENT = "Upset but cooperative.";
	private static final IncidentDetails NOTHING_YET = new IncidentDetails(null, null, null, null, null, null, Set.of());
	private static final String NEW_CLAIM = """
		From: alice@example.com, sent 2026-10-08
		My car hit another vehicle at the junction near Elm Street yesterday at 14:30.
		""";

	private static final String SUMMARY_PROMPT = "Summarize the following claim correspondence";
	private static final String SENTIMENT_PROMPT = "Assess the claimant's sentiment";
	private static final String DETAILS_PROMPT = "Extract the incident details from the following correspondence";
	private static final String DATE_REPROMPT = "incidentDate must be a valid yyyy-MM-dd date string, or null.";

	// The leaf agents' calls each take this long, so a sequential run would take at least three times as long
	private static final int DELAY_MS = 750;

	@Inject
	ObjectMapper objectMapper;

	@Inject
	ClaimExtractionWorkflow workflow;

	WireMock wiremock;

	@BeforeEach
	void setUp() {
		this.wiremock.resetMappings();
		this.wiremock.resetRequests();
		stub(SUMMARY_PROMPT, SUMMARY);
		stub(SENTIMENT_PROMPT, SENTIMENT);
	}

	@Test
	void completeExtractionRunsTheThreeAgentsInParallelWithOneStatelessUserMessageEach() {
		stub(DETAILS_PROMPT, detailsJson("2026-10-07", "14:30", "Elm Street junction", "MULTIPLE_VEHICLE", "[]"));

		var extraction = this.workflow.extractClaim(NEW_CLAIM, NOTHING_YET, Set.of(), SENT_DATE);

		assertThat(extraction)
			.isEqualTo(new ClaimExtraction(SUMMARY, SENTIMENT, details("2026-10-07", "14:30", "Elm Street junction", ClaimCategory.MULTIPLE_VEHICLE, Set.of())));
		assertThat(ClaimExtractionRules.findMissingItems(extraction, Set.of(), false))
			.isEmpty();

		var requests = chatRequests();
		assertThat(requests)
			.hasSize(3)
			.allSatisfy(request -> assertThat(roles(request))
				.as("a system prompt and one user message: no chat history, no RAG content")
				.containsExactly("system", "user"))
			.allSatisfy(request -> assertThat(userMessage(request)).contains(NEW_CLAIM.strip()));

		// Parallel: every call reached WireMock before the first one was answered
		var lastArrival = requests.stream()
			.map(ServeEvent::getRequest)
			.map(r -> r.getLoggedDate().toInstant())
			.max(Comparator.naturalOrder())
			.orElseThrow();
		var firstAnswer = requests.stream()
			.map(event -> event.getRequest().getLoggedDate().toInstant().plusMillis(event.getTiming().getTotalTime()))
			.min(Comparator.naturalOrder())
			.orElseThrow();
		assertThat(lastArrival)
			.isBefore(firstAnswer);
	}

	@Test
	void extractionWithMissingFieldsReportsThem() {
		stub(DETAILS_PROMPT, detailsJson(null, null, null, "OTHER", "[]"));

		var extraction = this.workflow.extractClaim("Something happened to my car.", NOTHING_YET, Set.of(), SENT_DATE);

		assertThat(ClaimExtractionRules.findMissingItems(extraction, Set.of(), false))
			.containsExactly(MissingItem.INCIDENT_DATE, MissingItem.LOCATION);
	}

	@Test
	void theSentDateReachesOnlyTheDetailsAgentSoItCanResolveRelativeDates() {
		stub(DETAILS_PROMPT, detailsJson("2026-10-07", null, "Elm Street", "SINGLE_VEHICLE", "[]"));

		var extraction = this.workflow.extractClaim("I hit a post on Elm Street last night.", NOTHING_YET, Set.of(), SENT_DATE);

		assertThat(extraction.details().incidentDate())
			.isEqualTo("2026-10-07");
		assertThat(userMessage(requestFor(DETAILS_PROMPT)))
			.contains("was sent on %s (ISO yyyy-MM-dd). Resolve relative dates".formatted(SENT_DATE));
		assertThat(List.of(userMessage(requestFor(SUMMARY_PROMPT)), userMessage(requestFor(SENTIMENT_PROMPT))))
			.allSatisfy(message -> assertThat(message).doesNotContain(SENT_DATE));
	}

	@Test
	void aDescribedIncidentThatFitsNoCategoryIsOther() {
		stub(DETAILS_PROMPT, detailsJson("2026-10-07", null, "Elm Street", "OTHER", "[]"));

		var extraction = this.workflow.extractClaim("A tree branch fell on my parked car on Elm Street.", NOTHING_YET, Set.of(), SENT_DATE);

		assertThat(extraction.details().category())
			.isEqualTo(ClaimCategory.OTHER);
		assertThat(ClaimExtractionRules.findMissingItems(extraction, Set.of(), false))
			.isEmpty();
	}

	@Test
	void aReplyOnAPendingClaimIsExtractedOverTheCombinedCorrespondenceWithWhatWeAlreadyKnow() {
		var combined = """
			%s
			--- Newest reply, sent 2026-10-09 ---
			It was the junction of Elm Street and Main Street.
			""".formatted(NEW_CLAIM);
		var soFar = details("2026-10-07", "14:30", null, ClaimCategory.MULTIPLE_VEHICLE, Set.of());
		stub(DETAILS_PROMPT, detailsJson("2026-10-07", "14:30", "Elm Street and Main Street", "MULTIPLE_VEHICLE", "[\"LOCATION\"]"));

		var extraction = this.workflow.extractClaim(combined, soFar, Set.of(MissingItem.LOCATION), "2026-10-09");

		assertThat(ClaimExtractionRules.findMissingItems(extraction, Set.of(MissingItem.LOCATION), false))
			.isEmpty();
		assertThat(userMessage(requestFor(DETAILS_PROMPT)))
			.contains(combined.strip())
			.contains("- incidentDate: 2026-10-07")
			.contains("- location: unknown")
			.contains("- category: MULTIPLE_VEHICLE")
			.contains("Items we asked the customer for: LOCATION");
	}

	@Test
	void theDetailsPromptListsEveryClaimCategoryFromTheEnum() {
		stub(DETAILS_PROMPT, detailsJson("2026-10-07", null, "Elm Street", "SINGLE_VEHICLE", "[]"));

		this.workflow.extractClaim(NEW_CLAIM, NOTHING_YET, Set.of(), SENT_DATE);

		var categories = Stream.of(ClaimCategory.values())
			.map(ClaimCategory::name)
			.collect(Collectors.joining(", "));
		assertThat(userMessage(requestFor(DETAILS_PROMPT)))
			.contains("- category: one of %s (OTHER if described but it fits no other category), or null".formatted(categories));
	}

	@Test
	void anEmptyReplyAfterAReviewerTickedItemsLeavesThemMissing() {
		var combined = """
			%s
			--- Newest reply, sent 2026-10-09 ---
			""".formatted(NEW_CLAIM);
		var soFar = details("2026-10-07", "14:30", "Elm Street junction", ClaimCategory.MULTIPLE_VEHICLE, Set.of());
		var ticked = Set.of(MissingItem.INCIDENT_DESCRIPTION, MissingItem.LOCATION);
		// The model wrongly claims the blank reply answered both
		stub(DETAILS_PROMPT, detailsJson("2026-10-07", "14:30", "Elm Street junction", "MULTIPLE_VEHICLE", "[\"INCIDENT_DESCRIPTION\",\"LOCATION\"]"));

		var extraction = this.workflow.extractClaim(combined, soFar, ticked, "2026-10-09");

		assertThat(ClaimExtractionRules.findMissingItems(extraction, ticked, true))
			.containsExactly(MissingItem.INCIDENT_DESCRIPTION, MissingItem.LOCATION);
		assertThat(userMessage(requestFor(DETAILS_PROMPT)))
			.contains("Items we asked the customer for: INCIDENT_DESCRIPTION, LOCATION");
	}

	@Test
	void anInvalidDateIsRepromptedAndTheCorrectedAnswerIsUsed() {
		stub(DETAILS_PROMPT, detailsJson("2026-13-40", null, "Elm Street", "SINGLE_VEHICLE", "[]"));
		stub(DATE_REPROMPT, detailsJson("2026-10-07", null, "Elm Street", "SINGLE_VEHICLE", "[]"));

		var extraction = this.workflow.extractClaim(NEW_CLAIM, NOTHING_YET, Set.of(), SENT_DATE);

		assertThat(extraction.details().incidentDate())
			.isEqualTo("2026-10-07");
		assertThat(requestFor(DATE_REPROMPT))
			.satisfies(reprompt -> assertThat(roles(reprompt))
				.as("the reprompt keeps the system prompt, the original request and the rejected answer")
				.containsExactly("system", "user", "assistant", "user"));
	}

	@Test
	void aDateTheModelNeverCorrectsIsTreatedAsMissingInsteadOfFailingTheRun() {
		var neverFixed = detailsJson("2026-13-40", "14:30", "Elm Street", "SINGLE_VEHICLE", "[]");
		stub(DETAILS_PROMPT, neverFixed);
		stub(DATE_REPROMPT, neverFixed);

		var extraction = this.workflow.extractClaim(NEW_CLAIM, NOTHING_YET, Set.of(), SENT_DATE);

		assertThat(extraction)
			.isEqualTo(new ClaimExtraction(SUMMARY, SENTIMENT, details(null, "14:30", "Elm Street", ClaimCategory.SINGLE_VEHICLE, Set.of())));
		assertThat(ClaimExtractionRules.findMissingItems(extraction, Set.of(), false))
			.containsExactly(MissingItem.INCIDENT_DATE);
	}

	@Test
	void malformedJsonThatIsNeverFixedStillFailsTheRun() {
		stub(DETAILS_PROMPT, "I'm sorry, I can't help with that.");
		stub(JsonExtractorOutputGuardrail.DEFAULT_REPROMPT_PROMPT, "Still not JSON.");

		assertThatExceptionOfType(RuntimeException.class)
			.isThrownBy(() -> this.workflow.extractClaim(NEW_CLAIM, NOTHING_YET, Set.of(), SENT_DATE));
	}

	private void stub(String lastMessageContains, String answer) {
		this.wiremock.register(post(urlPathEqualTo("/v1/chat/completions"))
			.withRequestBody(matchingJsonPath("$.messages[-1:].content", containing(lastMessageContains)))
			.willReturn(okJson(openAiResponse(answer)).withFixedDelay(DELAY_MS)));
	}

	private List<ServeEvent> chatRequests() {
		return this.wiremock.getServeEvents().stream()
			.filter(event -> event.getRequest().getUrl().endsWith("/chat/completions"))
			.toList();
	}

	private ServeEvent requestFor(String lastMessageContains) {
		return chatRequests().stream()
			.filter(event -> lastMessage(event).contains(lastMessageContains))
			.findFirst()
			.orElseThrow(() -> new AssertionError("No request whose last message contains: " + lastMessageContains));
	}

	private String userMessage(ServeEvent event) {
		return messages(event).stream()
			.filter(message -> "user".equals(message.path("role").asText()))
			.map(message -> message.path("content").asText())
			.findFirst()
			.orElseThrow();
	}

	private String lastMessage(ServeEvent event) {
		var messages = messages(event);
		return messages.getLast()
			.path("content")
			.asText();
	}

	private List<String> roles(ServeEvent event) {
		return messages(event).stream()
			.map(message -> message.path("role").asText())
			.toList();
	}

	private List<JsonNode> messages(ServeEvent event) {
		try {
			return StreamSupport.stream(this.objectMapper.readTree(event.getRequest().getBodyAsString()).path("messages").spliterator(), false)
				.toList();
		}
		catch (Exception e) {
			throw new AssertionError("Couldn't parse the request body", e);
		}
	}

	private static IncidentDetails details(String date, String time, String location, ClaimCategory category, Set<MissingItem> answered) {
		return new IncidentDetails("Car hit another vehicle.", date, time, location, category, null, answered);
	}

	private static String detailsJson(String date, String time, String location, String category, String answered) {
		return """
			{"description":"Car hit another vehicle.","incidentDate":%s,"incidentTime":%s,"location":%s,"category":%s,"policyNumber":null,"answeredItems":%s}"""
			.formatted(jsonString(date), jsonString(time), jsonString(location), jsonString(category), answered);
	}

	private static String jsonString(String value) {
		return (value == null) ? "null" : "\"%s\"".formatted(value);
	}

	private String openAiResponse(String content) {
		try {
			return this.objectMapper.writeValueAsString(Map.of(
				"id", "chatcmpl-test",
				"object", "chat.completion",
				"created", 1234567890,
				"model", "claim-intake-model",
				"choices", List.of(Map.of(
					"index", 0,
					"message", Map.of("role", "assistant", "content", content),
					"finish_reason", "stop")),
				"usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2)));
		}
		catch (Exception e) {
			throw new AssertionError("Couldn't build the stub response", e);
		}
	}
}
