package ai.scoring.conversation.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ai.scoring.conversation.ConversationContext;
import ai.scoring.conversation.ConversationEvents;
import io.quarkiverse.langchain4j.chatscopes.ChatScope;
import io.quarkiverse.langchain4j.chatscopes.ChatScopeActivated;
import io.quarkiverse.langchain4j.chatscopes.ChatScopeDeactivated;
import io.quarkiverse.langchain4j.chatscopes.ChatScopeEnded;
import io.quarkiverse.langchain4j.chatscopes.ChatScopeStarted;

class ConversationalBaggageHandlerTests {
	private static final String SCOPE_ID = "chat-scope-1";

	private final ConversationContext conversationContext = new ConversationContext();
	private final ConversationEvents conversationEvents = mock(ConversationEvents.class);
	private final ConversationalBaggageHandler handler = new ConversationalBaggageHandler(this.conversationContext, this.conversationEvents);
	private final ChatScope scope = mock(ChatScope.class);

	@BeforeEach
	void setup() {
		when(this.scope.getId()).thenReturn(SCOPE_ID);
	}

	@Test
	void anActiveChatScopeIsTheCurrentConversation() {
		this.handler.conversationStarted(new ChatScopeStarted(this.scope));
		this.handler.conversationActivated(new ChatScopeActivated(this.scope));
		var whileActive = this.conversationContext.currentId();

		this.handler.conversationDeactivated(new ChatScopeDeactivated(this.scope));

		assertThat(whileActive).contains(SCOPE_ID);
		assertThat(this.conversationContext.currentId()).isEmpty();
		verifyNoInteractions(this.conversationEvents);
	}

	@Test
	void endingAChatScopeFiresOneConversationEndedEventWithTheScopeId() {
		this.handler.conversationStarted(new ChatScopeStarted(this.scope));
		this.handler.conversationActivated(new ChatScopeActivated(this.scope));

		this.handler.conversationEnded(new ChatScopeEnded(this.scope));

		assertThat(this.conversationContext.currentId()).isEmpty();
		verify(this.conversationEvents).ended(SCOPE_ID);
		verifyNoMoreInteractions(this.conversationEvents);
	}

	@Test
	void aChatScopeStartedInsideAnotherConversationKeepsThatConversation() {
		this.conversationContext.runIn("outer", () -> {
			this.handler.conversationStarted(new ChatScopeStarted(this.scope));
			this.handler.conversationActivated(new ChatScopeActivated(this.scope));

			assertThat(this.conversationContext.currentId()).contains("outer");
		});
	}
}
