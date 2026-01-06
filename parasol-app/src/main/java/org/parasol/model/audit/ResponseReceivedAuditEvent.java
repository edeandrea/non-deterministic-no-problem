package org.parasol.model.audit;

import static org.parasol.model.audit.ResponseReceivedAuditEvent.EVENT_TYPE;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue(EVENT_TYPE)
public class ResponseReceivedAuditEvent extends AuditEvent {
	public static final String EVENT_TYPE = "RESPONSE_RECEIVED";

	@Column(updatable = false, columnDefinition = "TEXT")
	private String response;

	@Column(updatable = false)
	private String modelName;

	@Column(updatable = false)
	private int inputTokenCount;

	@Column(updatable = false)
	private int outputTokenCount;

	// JPA requires a no-arg constructor with at least protected visibility
	protected ResponseReceivedAuditEvent() {
		super();
	}

	private ResponseReceivedAuditEvent(Builder builder) {
		super(builder);
		this.response = builder.response;
		this.modelName = builder.modelName;
		this.inputTokenCount = builder.inputTokenCount;
		this.outputTokenCount = builder.outputTokenCount;
	}

	@Override
	public AuditEventType getEventType() {
		return AuditEventType.RESPONSE_RECEIVED;
	}

	public Builder toBuilder() {
		return new Builder(this);
	}

	public static Builder builder() {
		return new Builder();
	}

	public int getInputTokenCount() {
		return inputTokenCount;
	}

	public void setInputTokenCount(int inputTokenCount) {
		this.inputTokenCount = inputTokenCount;
	}

	public int getTokenCount() {
		return this.inputTokenCount + this.outputTokenCount;
	}

	public String getModelName() {
		return modelName;
	}

	public void setModelName(String modelName) {
		this.modelName = modelName;
	}

	public int getOutputTokenCount() {
		return outputTokenCount;
	}

	public void setOutputTokenCount(int outputTokenCount) {
		this.outputTokenCount = outputTokenCount;
	}

	public String getResponse() {
		return response;
	}

	public void setResponse(String response) {
		this.response = response;
	}

	@Override
	public String toString() {
		return "ResponseReceivedAuditEvent{" +
			"eventType='" + getEventType() + '\'' +
			", inputTokenCount=" + getInputTokenCount() +
			", response='" + getResponse() + '\'' +
			", modelName='" + getModelName() + '\'' +
			", outputTokenCount=" + getOutputTokenCount() +
			", id=" + getId() +
			", invocationContext=" + getInvocationContext() +
			'}';
	}

	public static final class Builder extends AuditEvent.Builder<Builder, ResponseReceivedAuditEvent> {
		private String response;
		private String modelName;
		private int inputTokenCount;
		private int outputTokenCount;

		private Builder() {
			super();
		}

		private Builder(ResponseReceivedAuditEvent source) {
			super(source);
			this.response = source.response;
			this.modelName = source.modelName;
			this.inputTokenCount = source.inputTokenCount;
			this.outputTokenCount = source.outputTokenCount;
		}

		public Builder response(String response) {
			this.response = response;
			return this;
		}

		public Builder modelName(String modelName) {
			this.modelName = modelName;
			return this;
		}

		public Builder inputTokenCount(int inputTokenCount) {
			this.inputTokenCount = inputTokenCount;
			return this;
		}

		public Builder outputTokenCount(int outputTokenCount) {
			this.outputTokenCount = outputTokenCount;
			return this;
		}

		@Override
		public ResponseReceivedAuditEvent build() {
			return new ResponseReceivedAuditEvent(this);
		}
	}
}
