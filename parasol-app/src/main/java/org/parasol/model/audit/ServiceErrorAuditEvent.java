package org.parasol.model.audit;

import static org.parasol.model.audit.ServiceErrorAuditEvent.EVENT_TYPE;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue(EVENT_TYPE)
public class ServiceErrorAuditEvent extends AuditEvent {
	public static final String EVENT_TYPE = "SERVICE_ERROR";

	@Column(updatable = false, columnDefinition = "TEXT")
	private String errorMessage;

	@Column(updatable = false, columnDefinition = "TEXT")
	private String causeErrorMessage;

	protected ServiceErrorAuditEvent() {
		// Required by JPA
	}

	private ServiceErrorAuditEvent(Builder builder) {
		super(builder);
		this.errorMessage = builder.errorMessage;
		this.causeErrorMessage = builder.causeErrorMessage;
	}

	@Override
	public AuditEventType getEventType() {
		return AuditEventType.SERVICE_ERROR;
	}

	public Builder toBuilder() {
		return new Builder(this);
	}

	public static Builder builder() {
		return new Builder();
	}

	public String getCauseErrorMessage() {
		return causeErrorMessage;
	}

	public void setCauseErrorMessage(String causeErrorMessage) {
		this.causeErrorMessage = causeErrorMessage;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}

	@Override
	public String toString() {
		return "ServiceErrorAuditEvent{" +
			"eventType='" + getEventType() + '\'' +
			", causeErrorMessage='" + getCauseErrorMessage() + '\'' +
			", errorMessage='" + getErrorMessage() + '\'' +
			", id=" + getId() +
			", invocationContext=" + getInvocationContext() +
			'}';
	}

	public static final class Builder extends AuditEvent.Builder<Builder, ServiceErrorAuditEvent> {
		private String errorMessage;
		private String causeErrorMessage;

		private Builder() {
			super();
		}

		private Builder(ServiceErrorAuditEvent source) {
			super(source);
			this.errorMessage = source.errorMessage;
			this.causeErrorMessage = source.causeErrorMessage;
		}

		public Builder errorMessage(String errorMessage) {
			this.errorMessage = errorMessage;
			return this;
		}

		public Builder causeErrorMessage(String causeErrorMessage) {
			this.causeErrorMessage = causeErrorMessage;
			return this;
		}

		@Override
		public ServiceErrorAuditEvent build() {
			return new ServiceErrorAuditEvent(this);
		}
	}
}
