package ai.scoring.domain.event;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class InvocationContext {
	@Column(updatable = false)
	private String applicationName;

	@Column(updatable = false)
	private String interfaceName;

	@Column(updatable = false)
	private String methodName;

	@Column(nullable = false, updatable = false)
	private UUID interactionId;

	@Column(nullable = false, updatable = false)
	private Instant interactionDate;

	public InvocationContext() {
		// Required by JPA
	}

	private InvocationContext(Builder builder) {
		this.applicationName = builder.applicationName;
		this.interfaceName = builder.interfaceName;
		this.methodName = builder.methodName;
		this.interactionId = builder.interactionId;
		this.interactionDate = builder.interactionDate;
	}

	public String getApplicationName() {
		return applicationName;
	}

	public void setApplicationName(String applicationName) {
		this.applicationName = applicationName;
	}

	public String getInterfaceName() {
		return interfaceName;
	}

	public String getMethodName() {
		return methodName;
	}

	public UUID getInteractionId() {
		return interactionId;
	}

	public void setInterfaceName(String interfaceName) {
		this.interfaceName = interfaceName;
	}

	public void setMethodName(String methodName) {
		this.methodName = methodName;
	}

	public void setInteractionId(UUID interactionId) {
		this.interactionId = interactionId;
	}

	public Instant getInteractionDate() {
		return interactionDate;
	}

	public void setInteractionDate(Instant interactionDate) {
		this.interactionDate = interactionDate;
	}

	public static Builder builder() {
		return new Builder();
	}

	public Builder toBuilder() {
		return new Builder(this);
	}

	@Override
	public String toString() {
		return "InvocationContext{" +
			"interfaceName='" + interfaceName + '\'' +
			", applicationName='" + applicationName + '\'' +
			", methodName='" + methodName + '\'' +
			", interactionId=" + interactionId +
			", interactionDate=" + interactionDate +
			'}';
	}

	// Builder
	public static final class Builder {
		private String applicationName;
		private String interfaceName;
		private String methodName;
		private UUID interactionId;
		private Instant interactionDate;

		private Builder() {
		}

		private Builder(InvocationContext source) {
			this.interfaceName = source.interfaceName;
			this.methodName = source.methodName;
			this.interactionId = source.interactionId;
			this.interactionDate = source.interactionDate;
			this.applicationName = source.applicationName;
		}

		public Builder applicationName(String applicationName) {
			this.applicationName = applicationName;
			return this;
		}

		public Builder interfaceName(String interfaceName) {
			this.interfaceName = interfaceName;
			return this;
		}

		public Builder methodName(String methodName) {
			this.methodName = methodName;
			return this;
		}

		public Builder interactionId(UUID interactionId) {
			this.interactionId = interactionId;
			return this;
		}

		public Builder interactionDate(Instant interactionDate) {
			this.interactionDate = interactionDate;
			return this;
		}

		public InvocationContext build() {
			return new InvocationContext(this);
		}
	}
}
