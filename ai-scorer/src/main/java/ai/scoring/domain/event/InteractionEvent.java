package ai.scoring.domain.event;

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

@Entity
@Table(name = "interaction_events")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "event_type", discriminatorType = DiscriminatorType.STRING)
public abstract class InteractionEvent {
	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "interaction_events_seq")
	@SequenceGenerator(name = "interaction_events_seq", allocationSize = 1, sequenceName = "interaction_events_seq")
	private Long id;

	@Embedded
	private InvocationContext invocationContext;

	@CreationTimestamp(source = SourceType.DB)
	@Column(updatable = false, nullable = false)
	private Instant createdOn;

	// JPA requires a no-arg constructor with at least protected visibility
	protected InteractionEvent() {
	}

	protected InteractionEvent(Builder builder) {
		this.id = builder.id;
		this.invocationContext = builder.invocationContext;
	}

	public Long getId() {
		return id;
	}

	public abstract InteractionEventType getEventType();

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
		if (!(o instanceof InteractionEvent that)) {
			return false;
		}

		return Objects.equals(id, that.id);
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(id);
	}

	public static abstract class Builder<T extends Builder, A extends InteractionEvent> {
		private Long id;
		private InteractionEvent eventType;
		private InvocationContext invocationContext;

		protected Builder() {
		}

		// Constructor to initialize builder from an existing InteractionEvent
		protected Builder(InteractionEvent source) {
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
