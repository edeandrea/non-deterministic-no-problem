package ai.scoring.conversation;

/**
 * Represents an event indicating the completion of a conversation.
 * The conversation completion event encapsulates details about
 * the specific conversation that has been finalized.
 */
public interface ConversationCompletedEvent {
	String getConversationId();
	Builder toBuilder();

	static Builder builder() {
		return new Builder();
	}

	final class Builder {
		String conversationId;

		protected Builder() {}

		protected Builder(ConversationCompletedEvent source) {
			this.conversationId = source.getConversationId();
		}

		public Builder conversationId(String conversationId) {
			this.conversationId = conversationId;
			return this;
		}

		public ConversationCompletedEvent build() {
			return new DefaultConversationCompletedEvent(this);
		}
	}
}
