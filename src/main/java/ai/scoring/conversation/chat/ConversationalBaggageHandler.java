package ai.scoring.conversation.chat;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import io.quarkus.logging.Log;

import ai.scoring.conversation.ConversationContext;
import ai.scoring.conversation.ConversationEvents;
import io.opentelemetry.context.Scope;
import io.quarkiverse.langchain4j.chatscopes.ChatScopeActivated;
import io.quarkiverse.langchain4j.chatscopes.ChatScopeDeactivated;
import io.quarkiverse.langchain4j.chatscopes.ChatScopeEnded;
import io.quarkiverse.langchain4j.chatscopes.ChatScopeStarted;

/**
 * The chat adapter of the conversation core: translates the quarkus-langchain4j chat-scope lifecycle into
 * {@link ConversationContext} (the scope id is the conversation id, current while the scope is active) and, when a
 * scope ends, fires a {@link ai.scoring.conversation.ConversationEndedEvent ConversationEndedEvent}.
 */
@ApplicationScoped
public class ConversationalBaggageHandler {
	private final ConversationContext conversationContext;
	private final ConversationEvents conversationEvents;
	private final ConversationTracker tracker = new ConversationTracker();

	public ConversationalBaggageHandler(ConversationContext conversationContext, ConversationEvents conversationEvents) {
		this.conversationContext = conversationContext;
		this.conversationEvents = conversationEvents;
	}

	public void conversationStarted(@Observes ChatScopeStarted event) {
		var conversationId = event.scope().getId();
		Log.debugf("Conversation started with ID: %s", conversationId);

		this.conversationContext.currentId()
			.ifPresentOrElse(
				existingId -> Log.debugf("Existing conversation ID %s found in baggage", existingId),
				() -> this.tracker.start(new ConversationState(conversationId, this.conversationContext))
			);
	}

	public void conversationActivated(@Observes ChatScopeActivated event) {
		this.tracker.activate(event.scope().getId());
	}

	public void conversationDeactivated(@Observes ChatScopeDeactivated event) {
		this.tracker.deactivate(event.scope().getId());
	}

	public void conversationEnded(@Observes ChatScopeEnded event) {
		var conversationId = event.scope().getId();
		Log.debugf("Conversation ended with ID: %s", conversationId);

		this.tracker.end(conversationId);
		this.conversationEvents.ended(conversationId);
	}

	private static class ConversationTracker {
		private final Map<String, ConversationState> conversations = new ConcurrentHashMap<>();

		void start(ConversationState conversation) {
			var conversationId = conversation.conversationId();

			Optional.ofNullable(this.conversations.put(conversationId, conversation))
				.ifPresent(previous -> {
					Log.warnf("Overwriting active conversation for ID: %s", conversationId);
					previous.deactivate();
				});
		}

		void activate(String conversationId) {
			Optional.ofNullable(this.conversations.get(conversationId))
				.ifPresent(ConversationState::activate);
		}

		void deactivate(String conversationId) {
			Optional.ofNullable(this.conversations.get(conversationId))
				.ifPresent(ConversationState::deactivate);
		}

		void end(String conversationId) {
			Optional.ofNullable(this.conversations.remove(conversationId))
				.ifPresent(ConversationState::deactivate);
		}
	}

	private static class ConversationState {
		private final String conversationId;
		private final ConversationContext conversationContext;
		private Scope baggageScope;

		ConversationState(String conversationId, ConversationContext conversationContext) {
			this.conversationId = conversationId;
			this.conversationContext = conversationContext;
		}

		String conversationId() {
			return this.conversationId;
		}

		void activate() {
			this.baggageScope = this.conversationContext.enter(this.conversationId);
		}

		void deactivate() {
			if (this.baggageScope != null) {
				this.baggageScope.close();
				this.baggageScope = null;
			}
		}
	}
}
