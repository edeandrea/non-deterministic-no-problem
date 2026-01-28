package ai.scoring.domain.event;

import static ai.scoring.domain.event.InteractionStartedEvent.EVENT_TYPE;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue(EVENT_TYPE)
public class InteractionStartedEvent extends InteractionEvent {
	public static final String EVENT_TYPE = "INTERACTION_STARTED";
	
	@Column(updatable = false, columnDefinition = "TEXT")
	private String systemMessage;
	
	@Column(updatable = false, columnDefinition = "TEXT")
	private String userMessage;
	
	// JPA requires a no-arg constructor with at least protected visibility
	protected InteractionStartedEvent() {
		super();
	}

	// Private constructor used by the builder
	private InteractionStartedEvent(Builder builder) {
		super(builder);
		this.systemMessage = builder.systemMessage;
		this.userMessage = builder.userMessage;
	}
	
	@Override
	public InteractionEventType getEventType() {
		return InteractionEventType.INTERACTION_STARTED;
	}

	public String getSystemMessage() {
		return systemMessage;
	}

	public void setSystemMessage(String systemMessage) {
		this.systemMessage = systemMessage;
	}

	public String getUserMessage() {
		return userMessage;
	}

	public void setUserMessage(String userMessage) {
		this.userMessage = userMessage;
	}

	public static Builder builder() {
		return new Builder();
	}

	public Builder toBuilder() {
		return new Builder(this);
	}

	@Override
	public String toString() {
		return "InteractionStartedEvent{" +
			"eventType='" + getEventType() + '\'' +
			", systemMessage='" + getSystemMessage() + '\'' +
			", userMessage='" + getUserMessage() + '\'' +
			", id=" + getId() +
			", invocationContext=" + getInvocationContext() +
			'}';
	}

	public static final class Builder extends InteractionEvent.Builder<Builder, InteractionStartedEvent> {
		private String systemMessage;
		private String userMessage;

		private Builder() {
			super();
		}

		private Builder(InteractionStartedEvent source) {
			super(source);
			this.systemMessage = source.systemMessage;
			this.userMessage = source.userMessage;
		}

		public Builder systemMessage(String systemMessage) {
			this.systemMessage = systemMessage;
			return this;
		}

		public Builder userMessage(String userMessage) {
			this.userMessage = userMessage;
			return this;
		}

		@Override
		public InteractionStartedEvent build() {
			return new InteractionStartedEvent(this);
		}
	}
}
