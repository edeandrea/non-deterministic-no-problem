package ai.scoring.domain.event;

import static ai.scoring.domain.event.InteractionCompletedEvent.EVENT_TYPE;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue(EVENT_TYPE)
public class InteractionCompletedEvent extends InteractionEvent {
	public static final String EVENT_TYPE = "INTERACTION_COMPLETED";

	@Column(updatable = false, columnDefinition = "TEXT")
	private String result;

	protected InteractionCompletedEvent() {
		super();
	}

	private InteractionCompletedEvent(Builder builder) {
		super(builder);
		this.result = builder.result;
	}

	@Override
	public InteractionEventType getEventType() {
		return InteractionEventType.INTERACTION_COMPLETED;
	}

	public String getResult() {
		return result;
	}

	public void setResult(String result) {
		this.result = result;
	}

	public Builder toBuilder() {
		return new Builder(this);
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public String toString() {
		return "InteractionCompletedEvent{" +
			"eventType='" + getEventType() + '\'' +
			", result='" + getResult() + '\'' +
			", id=" + getId() +
			", invocationContext=" + getInvocationContext() + '}';
	}

	public static final class Builder extends InteractionEvent.Builder<Builder, InteractionCompletedEvent> {
		private String result;

		private Builder() {
			super();
		}

		private Builder(InteractionCompletedEvent source) {
			super(source);
			this.result = source.result;
		}

		public Builder result(String result) {
			this.result = result;
			return this;
		}

		@Override
		public InteractionCompletedEvent build() {
			return new InteractionCompletedEvent(this);
		}
	}
}
