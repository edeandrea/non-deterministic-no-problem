package ai.scoring.conversation;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import io.quarkus.logging.Log;

import ai.scoring.evaluation.SessionScoringService;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes;
import io.quarkiverse.langchain4j.chatscopes.ChatScopeActivated;
import io.quarkiverse.langchain4j.chatscopes.ChatScopeDeactivated;
import io.quarkiverse.langchain4j.chatscopes.ChatScopeEnded;
import io.quarkiverse.langchain4j.chatscopes.ChatScopeStarted;
import io.smallrye.mutiny.infrastructure.Infrastructure;

/**
 * A listener class that manages the lifecycle events of conversational scopes. This class
 * enables the tracking and handling of conversations, including their activation, deactivation,
 * and successful termination. It integrates with services to process sessions and utilizes
 * baggage propagation for context management.
 */
@ApplicationScoped
public class ConversationalBaggageHandler {
	private static final String CONVERSATION_ID_KEY = GenAiIncubatingAttributes.GEN_AI_CONVERSATION_ID.getKey();

	private final SessionScoringService sessionScoringService;
	private final ConversationTracker tracker = new ConversationTracker();

	public ConversationalBaggageHandler(SessionScoringService sessionScoringService) {
		this.sessionScoringService = sessionScoringService;
	}

	public void conversationStarted(@Observes ChatScopeStarted event) {
		var conversationId = event.scope().getId();
		Log.debugf("Conversation started with ID: %s", conversationId);

		Optional.ofNullable(Baggage.current().getEntryValue(CONVERSATION_ID_KEY))
			.ifPresentOrElse(
				existingId -> Log.debugf("Existing conversation ID %s found in baggage", existingId),
				() -> this.tracker.start(conversationId)
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

		Infrastructure.getDefaultExecutor().execute(() -> sessionScoringService.scoreSession(conversationId));
	}

	private static class ConversationTracker {
		private final Map<String, ConversationState> conversations = new ConcurrentHashMap<>();

		void start(String conversationId) {
			Optional.ofNullable(this.conversations.put(conversationId, new ConversationState(conversationId)))
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
		private Scope baggageScope;

		ConversationState(String conversationId) {
			this.conversationId = conversationId;
		}

		void activate() {
			this.baggageScope = Baggage.current().toBuilder()
				.put(CONVERSATION_ID_KEY, this.conversationId)
				.build()
				.storeInContext(Context.current())
				.makeCurrent();
		}

		void deactivate() {
			if (this.baggageScope != null) {
				this.baggageScope.close();
				this.baggageScope = null;
			}
		}
	}
}
