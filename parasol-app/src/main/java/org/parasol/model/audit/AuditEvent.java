package org.parasol.model.audit;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;

/**
 * Entity to store AI audit events.
 *
 * This model is intentionally generic to accommodate all event types.
 */
@Entity
@Table(name = "audit_events")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "event_type", discriminatorType = DiscriminatorType.STRING)
public abstract class AuditEvent {
	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "audit_events_seq")
	@SequenceGenerator(name = "audit_events_seq", allocationSize = 1, sequenceName = "audit_events_seq")
	private Long id;

	/**
	 * Source information provided by the event (e.g., class/method or custom info).
	 */
	@Embedded
	private InvocationContext invocationContext;

	@CreationTimestamp(source = SourceType.DB)
	@Column(updatable = false, nullable = false)
	private Instant createdOn;

	// JPA requires a no-arg constructor with at least protected visibility
	protected AuditEvent() {
	}

	protected AuditEvent(Builder builder) {
		this.id = builder.id;
		this.invocationContext = builder.invocationContext;
	}

	public Long getId() {
		return id;
	}

	public abstract AuditEventType getEventType();

	public InvocationContext getInvocationContext() {
		return invocationContext;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public void setInvocationContext(InvocationContext sourceInfo) {
		this.invocationContext = sourceInfo;
	}

	public Instant getCreatedOn() {
		return createdOn;
	}

	public void setCreatedOn(Instant createdOn) {
		this.createdOn = createdOn;
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof AuditEvent that)) {
			return false;
		}

		return Objects.equals(id, that.id);
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(id);
	}

	public static abstract class Builder<T extends Builder, A extends AuditEvent> {
		private Long id;
		private AuditEventType eventType;
		private InvocationContext invocationContext;

		protected Builder() {
		}

		// Constructor to initialize builder from an existing AuditEvent
		protected Builder(AuditEvent source) {
			this.id = source.id;
			this.invocationContext = source.invocationContext;
		}

		public T id(Long id) {
			this.id = id;
			return (T) this;
		}

		public T invocationContext(InvocationContext sourceInfo) {
			this.invocationContext = sourceInfo;
			return (T) this;
		}

		public abstract A build();
	}
}
