package org.parasol.model.audit;

import static org.parasol.model.audit.ToolExecutedAuditEvent.EVENT_TYPE;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue(EVENT_TYPE)
public class ToolExecutedAuditEvent extends AuditEvent {
	public static final String EVENT_TYPE = "TOOL_EXECUTED";

	@Column(updatable = false)
	private String toolName;

	@Column(updatable = false, columnDefinition = "TEXT")
	private String toolArgs;

	@Column(updatable = false, columnDefinition = "TEXT")
	private String toolResult;

	protected ToolExecutedAuditEvent() {
		super();
	}

	private ToolExecutedAuditEvent(Builder builder) {
		super(builder);
		this.toolName = builder.toolName;
		this.toolArgs = builder.toolArgs;
		this.toolResult = builder.result;
	}

	@Override
	public AuditEventType getEventType() {
		return AuditEventType.TOOL_EXECUTED;
	}

	public Builder toBuilder() {
		return new Builder(this);
	}

	public static Builder builder() {
		return new Builder();
	}

	public String getToolResult() {
		return toolResult;
	}

	public void setToolResult(String result) {
		this.toolResult = result;
	}

	public String getToolArgs() {
		return toolArgs;
	}

	public void setToolArgs(String toolArgs) {
		this.toolArgs = toolArgs;
	}

	public String getToolName() {
		return toolName;
	}

	public void setToolName(String toolName) {
		this.toolName = toolName;
	}

	@Override
	public String toString() {
		return "ToolExecutedAuditEvent{" +
			"toolResult='" + getToolResult() + '\'' +
			", toolName='" + getToolName() + '\'' +
			", toolArgs='" + getToolArgs() + '\'' +
			", id=" + getId() +
			", invocationContext=" + getInvocationContext() + '}';
	}

	public static final class Builder extends AuditEvent.Builder<Builder, ToolExecutedAuditEvent> {
		private String toolName;
		private String toolArgs;
		private String result;

		private Builder() {
			super();
		}

		private Builder(ToolExecutedAuditEvent source) {
			super(source);
			this.toolName = source.toolName;
			this.toolArgs = source.toolArgs;
			this.result = source.toolResult;
		}

		public Builder toolName(String toolName) {
			this.toolName = toolName;
			return this;
		}

		public Builder toolArgs(String toolArgs) {
			this.toolArgs = toolArgs;
			return this;
		}

		public Builder result(String result) {
			this.result = result;
			return this;
		}

		@Override
		public ToolExecutedAuditEvent build() {
			return new ToolExecutedAuditEvent(this);
		}
	}
}
