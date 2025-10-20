package org.parasol.model.audit;


import static org.parasol.model.audit.ServiceCompleteAuditEvent.EVENT_TYPE;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue(EVENT_TYPE)
public class ServiceCompleteAuditEvent extends AuditEvent {
	public static final String EVENT_TYPE = "SERVICE_COMPLETED";

	@Column(updatable = false, columnDefinition = "TEXT")
	private String result;

	protected ServiceCompleteAuditEvent() {
		super();
	}

	private ServiceCompleteAuditEvent(Builder builder) {
		super(builder);
		this.result = builder.result;
	}

	@Override
	public AuditEventType getEventType() {
		return AuditEventType.SERVICE_COMPLETED;
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
		return "ServiceCompleteAuditEvent{" +
			"eventType='" + getEventType() + '\'' +
			", result='" + getResult() + '\'' +
			", id=" + getId() +
			", invocationContext=" + getInvocationContext() + '}';
	}

	public static final class Builder extends AuditEvent.Builder<Builder, ServiceCompleteAuditEvent> {
		private String result;

		private Builder() {
			super();
		}

		private Builder(ServiceCompleteAuditEvent source) {
			super(source);
			this.result = source.result;
		}

		public Builder result(String result) {
			this.result = result;
			return this;
		}

		@Override
		public ServiceCompleteAuditEvent build() {
			return new ServiceCompleteAuditEvent(this);
		}
	}
}
