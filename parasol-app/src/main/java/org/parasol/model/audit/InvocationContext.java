package org.parasol.model.audit;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class InvocationContext {
	@Column(updatable = false)
	private String interfaceName;

	@Column(updatable = false)
	private String methodName;

	@Column(updatable = false)
	private UUID interactionId;

	public InvocationContext() {
		// Required by JPA
	}

	private InvocationContext(Builder builder) {
		this.interfaceName = builder.interfaceName;
		this.methodName = builder.methodName;
		this.interactionId = builder.interactionId;
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
			", methodName='" + methodName + '\'' +
			", interactionId=" + interactionId +
			'}';
	}

	// Builder
	public static final class Builder {
		private String interfaceName;
		private String methodName;
		private UUID interactionId;

		private Builder() {
		}

		private Builder(InvocationContext source) {
			this.interfaceName = source.interfaceName;
			this.methodName = source.methodName;
			this.interactionId = source.interactionId;
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

		public InvocationContext build() {
			return new InvocationContext(this);
		}
	}
}
