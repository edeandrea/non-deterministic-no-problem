package ai.scoring.conversation;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;

import org.jboss.logging.Logger;

/**
 * Fires the conversation lifecycle events. Every event is fired asynchronously, and any failure of its observers is
 * logged: an exception thrown by an {@code @ObservesAsync} observer surfaces nowhere else, so a broken consumer would
 * otherwise fail silently.
 */
@ApplicationScoped
public class ConversationEvents {
	// JBoss Logging rather than io.quarkus.logging.Log: this package must not depend on Quarkus
	private static final Logger LOG = Logger.getLogger(ConversationEvents.class);

	private final Event<ConversationEndedEvent> conversationEnded;

	public ConversationEvents(Event<ConversationEndedEvent> conversationEnded) {
		this.conversationEnded = conversationEnded;
	}

	/**
	 * Fires a {@link ConversationEndedEvent} asynchronously.
	 *
	 * @param conversationId the id of the conversation that ended
	 * @return completes once every observer has run, exceptionally if any of them failed (the failure is already logged)
	 */
	public CompletionStage<ConversationEndedEvent> ended(String conversationId) {
		return this.conversationEnded.fireAsync(new ConversationEndedEvent(conversationId))
			.whenComplete((_, failure) -> logFailure(conversationId, failure));
	}

	private static void logFailure(String conversationId, Throwable failure) {
		Optional.ofNullable(failure)
			.ifPresent(f -> LOG.errorf(f, "A ConversationEndedEvent observer failed for conversation %s", conversationId));
	}
}
