package org.parasol.model.audit;

import static org.parasol.model.audit.InputGuardrailExecutedAuditEvent.EVENT_TYPE;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue(EVENT_TYPE)
public class InputGuardrailExecutedAuditEvent extends AuditEvent {
	public static final String EVENT_TYPE = "INPUT_GUARDRAIL_EXECUTED";

	@Column(updatable = false, columnDefinition = "TEXT")
	private String userMessage;

	@Column(updatable = false, columnDefinition = "TEXT")
	private String rewrittenUserMessage;

	@Column(updatable = false, columnDefinition = "TEXT")
	private String result;

	@Column(updatable = false)
	private String guardrailClass;
	
	// JPA requires a public or protected no-arg constructor
	protected InputGuardrailExecutedAuditEvent() {
		super();
	}

	// Private constructor used by the builder
	private InputGuardrailExecutedAuditEvent(Builder builder) {
		super(builder);
		this.userMessage = builder.userMessage;
		this.rewrittenUserMessage = builder.rewrittenUserMessage;
		this.result = builder.result;
		this.guardrailClass = builder.guardrailClass;
	}

	public static Builder builder() {
		return new Builder();
	}

	public Builder toBuilder() {
		return new Builder(this);
	}

	@Override
	public AuditEventType getEventType() {
		return AuditEventType.INPUT_GUARDRAIL_EXECUTED;
	}

	public String getGuardrailClass() {
		return guardrailClass;
	}

	public void setGuardrailClass(String guardrailClass) {
		this.guardrailClass = guardrailClass;
	}

	public String getResult() {
		return result;
	}

	public void setResult(String result) {
		this.result = result;
	}

	public String getRewrittenUserMessage() {
		return rewrittenUserMessage;
	}

	public void setRewrittenUserMessage(String rewrittenUserMessage) {
		this.rewrittenUserMessage = rewrittenUserMessage;
	}

	public String getUserMessage() {
		return userMessage;
	}

	public void setUserMessage(String userMessage) {
		this.userMessage = userMessage;
	}

	@Override
	public String toString() {
		return "InputGuardrailExecutedAuditEvent{" +
			"eventType='" + getEventType() + '\'' +
			", guardrailClass='" + getGuardrailClass() + '\'' +
			", userMessage='" + getUserMessage() + '\'' +
			", rewrittenUserMessage='" + getRewrittenUserMessage() + '\'' +
			", result='" + getResult() + '\'' +
			", id=" + getId() +
			", invocationContext=" + getInvocationContext() +
			'}';
	}

	public static final class Builder extends AuditEvent.Builder<Builder, InputGuardrailExecutedAuditEvent> {
		private String userMessage;
		private String rewrittenUserMessage;
		private String result;
		private String guardrailClass;

		private Builder() {
			super();
		}

		private Builder(InputGuardrailExecutedAuditEvent source) {
			super(source);
			this.userMessage = source.userMessage;
			this.rewrittenUserMessage = source.rewrittenUserMessage;
			this.result = source.result;
			this.guardrailClass = source.guardrailClass;
		}

		public Builder userMessage(String userMessage) {
			this.userMessage = userMessage;
			return this;
		}

		public Builder rewrittenUserMessage(String rewrittenUserMessage) {
			this.rewrittenUserMessage = rewrittenUserMessage;
			return this;
		}

		public Builder result(String result) {
			this.result = result;
			return this;
		}

		public Builder guardrailClass(String guardrailClass) {
			this.guardrailClass = guardrailClass;
			return this;
		}

		public InputGuardrailExecutedAuditEvent build() {
			return new InputGuardrailExecutedAuditEvent(this);
		}
	}
}
