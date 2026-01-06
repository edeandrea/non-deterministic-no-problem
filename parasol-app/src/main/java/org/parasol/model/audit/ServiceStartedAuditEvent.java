package org.parasol.model.audit;

import static org.parasol.model.audit.ServiceStartedAuditEvent.EVENT_TYPE;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue(EVENT_TYPE)
public class ServiceStartedAuditEvent extends AuditEvent {
	public static final String EVENT_TYPE = "SERVICE_STARTED";

	@Column(updatable = false, columnDefinition = "TEXT")
	private String systemMessage;
	
	@Column(updatable = false, columnDefinition = "TEXT")
	private String userMessage;

	// JPA requires a no-arg constructor with at least protected visibility
	protected ServiceStartedAuditEvent() {
		super();
	}

	// Private constructor used by the builder
	private ServiceStartedAuditEvent(Builder builder) {
		super(builder);
		this.systemMessage = builder.systemMessage;
		this.userMessage = builder.userMessage;
	}
	
	@Override
	public AuditEventType getEventType() {
		return AuditEventType.SERVICE_STARTED;
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
		return "ServiceStartedAuditEvent{" +
			"eventType='" + getEventType() + '\'' +
			", systemMessage='" + getSystemMessage() + '\'' +
			", userMessage='" + getUserMessage() + '\'' +
			", id=" + getId() +
			", invocationContext=" + getInvocationContext() +
			'}';
	}

	public static final class Builder extends AuditEvent.Builder<Builder, ServiceStartedAuditEvent> {
		private String systemMessage;
		private String userMessage;

		private Builder() {
			super();
		}

		private Builder(ServiceStartedAuditEvent source) {
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
		public ServiceStartedAuditEvent build() {
			return new ServiceStartedAuditEvent(this);
		}
	}
}
