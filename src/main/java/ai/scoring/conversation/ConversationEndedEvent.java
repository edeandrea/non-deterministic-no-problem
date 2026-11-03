package ai.scoring.conversation;

/**
 * Fired asynchronously (through {@link ConversationEvents#ended(String)}) when a conversation ends. Observe it with
 * {@code @ObservesAsync}.
 *
 * @param conversationId the id of the conversation that ended
 */
public record ConversationEndedEvent(String conversationId) {
}
