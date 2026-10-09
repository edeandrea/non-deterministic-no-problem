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
	private static final String CHAT_PATH = "/v1/chat/completions";

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
		this.wiremock.register(post(urlPathEqualTo(CHAT_PATH))
			.withRequestBody(matchingJsonPath("$.messages[-1:].content", containing(lastMessageContains)))
			.willReturn(okJson(completion(Map.of("role", "assistant", "content", answer), "stop")).withFixedDelay(delayMs)));
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
				"model", IntakeAgentsTestProfile.MODEL_NAME,
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
