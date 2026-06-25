package org.parasol.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.parasol.ai.ClaimService;
import org.parasol.model.claim.ClaimBotQuery;

import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.websockets.next.BasicWebSocketConnector;

import io.quarkiverse.langchain4j.chatscopes.SystemFailure;
import io.quarkiverse.langchain4j.chatscopes.websocket.WebsocketChatRoutes;

@QuarkusTest
class ClaimWebsocketChatBotTests {
	private static final String CLAIM = "This is the claim details";
	private static final String QUERY = "Should I approve this claim?";
	private static final LocalDate INCEPTION_DATE = LocalDate.of(1954, 9, 30);
	private static final List<String> RESPONSE = List.of("You", "should", "not", "approve", "this", "claim");
	private static final ArgumentMatcher<ClaimBotQuery> CHAT_SERVICE_MATCHER = query ->
			Objects.nonNull(query) &&
				(query.claimId() == 1L) &&
				QUERY.equals(query.query()) &&
				CLAIM.equals(query.claim());

	@InjectMock
	ClaimService claimService;

	@TestHTTPResource("/_chat/routes")
	URI chatRoutesUri;

	@Test
	void chatBotWorks() {
		var reply = RESPONSE.stream().collect(Collectors.joining(" "));
		when(this.claimService.chat(argThat(CHAT_SERVICE_MATCHER)))
			.thenReturn(reply);

		var messages = new ArrayList<String>();

		try (var client = createClient()) {
			var session = client.builder()
				.messageHandler(messages::add)
				.connect("chat");

			session.chat(Map.of("query", new ClaimBotQuery(1, CLAIM, QUERY, INCEPTION_DATE)));

			assertThat(messages)
				.singleElement()
				.isEqualTo(reply);
		}

		verify(this.claimService).chat(argThat(CHAT_SERVICE_MATCHER));
		verifyNoMoreInteractions(this.claimService);
	}

	@Test
	void chatBotHandlesError() {
		var error = new IllegalArgumentException("Something bad happened");
		when(this.claimService.chat(argThat(CHAT_SERVICE_MATCHER)))
			.thenThrow(error);

		try (var client = createClient()) {
			var session = client.builder()
				.connect("chat");

			assertThatThrownBy(() -> session.chat(Map.of("query", new ClaimBotQuery(1, CLAIM, QUERY, INCEPTION_DATE))))
				.isInstanceOf(SystemFailure.class)
				.hasMessage("ServerError");
		}

		verify(this.claimService).chat(argThat(CHAT_SERVICE_MATCHER));
		verifyNoMoreInteractions(this.claimService);
	}

	private WebsocketChatRoutes.Client createClient() {
		var connector = BasicWebSocketConnector.create();
		connector.baseUri(chatRoutesUri);
		return WebsocketChatRoutes.newClient(connector);
	}
}
