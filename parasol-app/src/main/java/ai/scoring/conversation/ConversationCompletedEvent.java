package ai.scoring.conversation;

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
