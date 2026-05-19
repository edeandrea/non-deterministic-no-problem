package ai.scoring.conversation;

final class DefaultConversationCompletedEvent implements ConversationCompletedEvent {
	private final String conversationId;

	DefaultConversationCompletedEvent(Builder builder) {
		this.conversationId = builder.conversationId;
	}

	@Override
	public String getConversationId() {
		return this.conversationId;
	}

	@Override
	public Builder toBuilder() {
		return new Builder(this);
	}
}
