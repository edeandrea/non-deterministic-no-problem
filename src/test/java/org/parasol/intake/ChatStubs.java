package org.parasol.intake;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

/**
 * Stubs the OpenAI-compatible chat endpoint for the intake agent tests and reads back what was sent.
 * <p>
 * Stubs match on the <b>last</b> message only (spike Q6), so a reprompt or a tool result, which arrives as a new last
 * message, gets its own stub.
 */
public final class ChatStubs {
	/**
	 * The model name every stubbed {@code claim-intake} request carries, under every profile. Must match
	 * {@code quarkus.langchain4j.openai.claim-intake.chat-model.model-name} in {@code %test}.
	 */
	public static final String MODEL_NAME = "claim-intake-model";

	private static final String CHAT_PATH = "/v1/chat/completions";

	// #221 spike: the classifier is a decision agent, so its stubs go to the System One endpoint instead
	private static final String CLASSIFY = "Classify the following email";
	private static final String DECIDE_PATH = "/v1/systemone";

	private final WireMock wiremock;
	private final ObjectMapper objectMapper;

	public ChatStubs(WireMock wiremock, ObjectMapper objectMapper) {
		this.wiremock = wiremock;
		this.objectMapper = objectMapper;
	}

	public void reset() {
		this.wiremock.resetMappings();
		this.wiremock.resetRequests();
	}

	public void answer(String lastMessageContains, String answer) {
		answer(lastMessageContains, answer, 0);
	}

	public void answer(String lastMessageContains, String answer, int delayMs) {
		if (CLASSIFY.equals(lastMessageContains)) {
			decide(List.of(), answer, delayMs);
			return;
		}
		this.wiremock.register(post(urlPathEqualTo(CHAT_PATH))
			.withRequestBody(matchingJsonPath("$.messages[-1:].content", containing(lastMessageContains)))
			.willReturn(okJson(completion(Map.of("role", "assistant", "content", answer), "stop")).withFixedDelay(delayMs)));
	}

	/**
	 * Answers requests whose last message contains <b>all</b> the given texts: a prompt phrase to pick the agent, and a
	 * marker from the email to pick the test.
	 */
	public void answerWhenAll(List<String> lastMessageContains, String answer) {
		answerWhenAll(lastMessageContains, answer, 0);
	}

	public void answerWhenAll(List<String> lastMessageContains, String answer, int delayMs) {
		if (lastMessageContains.contains(CLASSIFY)) {
			decide(lastMessageContains.stream().filter(text -> !CLASSIFY.equals(text)).toList(), answer, delayMs);
			return;
		}
		var mapping = post(urlPathEqualTo(CHAT_PATH));

		for (var text : lastMessageContains) {
			mapping = mapping.withRequestBody(matchingJsonPath("$.messages[-1:].content", containing(text)));
		}

		this.wiremock.register(mapping.willReturn(okJson(completion(Map.of("role", "assistant", "content", answer), "stop")).withFixedDelay(delayMs)));
	}

	/**
	 * Fails requests whose last message contains <b>all</b> the given texts, as a provider outage would.
	 */
	public void failWhenAll(List<String> lastMessageContains) {
		if (lastMessageContains.contains(CLASSIFY)) {
			var decision = post(urlPathEqualTo(DECIDE_PATH));
			for (var text : lastMessageContains.stream().filter(text -> !CLASSIFY.equals(text)).toList()) {
				decision = decision.withRequestBody(matchingJsonPath("$.state.correspondence", containing(text)));
			}
			this.wiremock.register(decision.willReturn(com.github.tomakehurst.wiremock.client.WireMock.serverError()));
			return;
		}
		var mapping = post(urlPathEqualTo(CHAT_PATH));

		for (var text : lastMessageContains) {
			mapping = mapping.withRequestBody(matchingJsonPath("$.messages[-1:].content", containing(text)));
		}

		this.wiremock.register(mapping.willReturn(com.github.tomakehurst.wiremock.client.WireMock.serverError()));
	}

	private void decide(List<String> stateContains, String choice, int delayMs) {
		var mapping = post(urlPathEqualTo(DECIDE_PATH)).withRequestBody(matchingJsonPath("$.questions.classifyEmail"));
		for (var text : stateContains) {
			mapping = mapping.withRequestBody(matchingJsonPath("$.state.correspondence", containing(text)));
		}
		var probabilities = new java.util.LinkedHashMap<String, Object>();
		for (var type : org.parasol.intake.model.EmailType.values()) {
			probabilities.put(type.name(), type.name().equals(choice) ? 0.9 : 0.05);
		}
		try {
			var body = this.objectMapper.writeValueAsString(Map.of(
				"model", "claim-decisions-model",
				"answers", Map.of("classifyEmail", Map.of("type", "choice", "choice", choice, "probabilities", probabilities, "confidence", 0.8)),
				"usage", Map.of("input_tokens", 1, "output_tokens", 0)));
			this.wiremock.register(mapping.willReturn(okJson(body).withFixedDelay(delayMs)));
		}
		catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	public void callTool(String lastMessageContains, String toolName, String argumentsJson) {
		var toolCall = Map.of(
			"id", "call_1",
			"type", "function",
			"function", Map.of("name", toolName, "arguments", argumentsJson));
		this.wiremock.register(post(urlPathEqualTo(CHAT_PATH))
			.withRequestBody(matchingJsonPath("$.messages[-1:].content", containing(lastMessageContains)))
			.willReturn(okJson(completion(Map.of("role", "assistant", "tool_calls", List.of(toolCall)), "tool_calls"))));
	}

	public List<ServeEvent> requests() {
		return this.wiremock.getServeEvents().stream()
			.filter(event -> event.getRequest().getUrl().endsWith("/chat/completions"))
			.toList();
	}

	public ServeEvent requestFor(String lastMessageContains) {
		return requests().stream()
			.filter(event -> lastMessage(event).contains(lastMessageContains))
			.findFirst()
			.orElseThrow(() -> new AssertionError("No request whose last message contains: " + lastMessageContains));
	}

	public long countRequestsFor(String userMessageContains) {
		return requests().stream()
			.filter(event -> userMessage(event).contains(userMessageContains))
			.count();
	}

	public String userMessage(ServeEvent event) {
		return messages(event).stream()
			.filter(message -> "user".equals(message.path("role").asText()))
			.map(message -> message.path("content").asText())
			.findFirst()
			.orElse("");
	}

	public String lastMessage(ServeEvent event) {
		return messages(event).getLast()
			.path("content")
			.asText();
	}

	public List<String> roles(ServeEvent event) {
		return messages(event).stream()
			.map(message -> message.path("role").asText())
			.toList();
	}

	public JsonNode body(ServeEvent event) {
		try {
			return this.objectMapper.readTree(event.getRequest().getBodyAsString());
		}
		catch (Exception e) {
			throw new AssertionError("Couldn't parse the request body", e);
		}
	}

	private List<JsonNode> messages(ServeEvent event) {
		return StreamSupport.stream(body(event).path("messages").spliterator(), false)
			.toList();
	}

	private String completion(Map<String, Object> message, String finishReason) {
		try {
			return this.objectMapper.writeValueAsString(Map.of(
				"id", "chatcmpl-test",
				"object", "chat.completion",
				"created", 1234567890,
				"model", MODEL_NAME,
				"choices", List.of(Map.of(
					"index", 0,
					"message", message,
					"finish_reason", finishReason)),
				"usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2)));
		}
		catch (Exception e) {
			throw new AssertionError("Couldn't build the stub response", e);
		}
	}
}
