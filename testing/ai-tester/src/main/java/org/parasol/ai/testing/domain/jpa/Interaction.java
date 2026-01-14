package org.parasol.ai.testing.domain.jpa;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "interactions")
public class Interaction {
	@Id
	@NotNull(message = "interactionId must not be null")
	private UUID interactionId;

	@NotNull(message = "interactionUri must not be null")
	private URI interactionUri;

	@Column(nullable = false)
	@NotNull(message = "interactionDate must not be null")
	private Instant interactionDate;

	@Column(columnDefinition = "TEXT")
	private String systemMessage;

	@Column(columnDefinition = "TEXT")
	private String userMessage;

	@Column(columnDefinition = "TEXT")
	private String result;

	@Column(nullable = false)
	@NotNull(message = "status must not be null")
	@Enumerated(EnumType.STRING)
	private Status status;

	@Column(columnDefinition = "TEXT")
	private String errorMessage;

	@Column(columnDefinition = "TEXT")
	private String causeErrorMessage;

	public Interaction() {
	}

	private Interaction(Builder builder) {
		this.interactionId = builder.interactionId;
		this.interactionUri = builder.interactionUri;
		this.interactionDate = builder.interactionDate;
		this.systemMessage = builder.systemMessage;
		this.userMessage = builder.userMessage;
		this.result = builder.result;
		this.status = Optional.ofNullable(builder.status).orElseGet(Status::getDefault);
		this.errorMessage = builder.errorMessage;
		this.causeErrorMessage = builder.causeErrorMessage;

		if (this.interactionId == null) {
			throw new IllegalArgumentException("interactionId must not be null");
		}

		if (this.interactionDate == null) {
			throw new IllegalArgumentException("interactionDate must not be null");
		}

		if (this.interactionUri == null) {
			throw new IllegalArgumentException("interactionUri must not be null");
		}

		if (this.status == null) {
			throw new IllegalArgumentException("status must not be null");
		}
	}

	public UUID getInteractionId() {
		return interactionId;
	}

	public URI getInteractionUri() {
		return interactionUri;
	}

	public Instant getInteractionDate() {
		return interactionDate;
	}

	public String getSystemMessage() {
		return systemMessage;
	}

	public String getUserMessage() {
		return userMessage;
	}

	public String getResult() {
		return result;
	}

	public Status getStatus() {
		return status;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public String getCauseErrorMessage() {
		return causeErrorMessage;
	}

	public Builder toBuilder() {
		return new Builder(this);
	}

	public static Builder builder() {
		return new Builder();
	}
	
	@Override
	public String toString() {
		return "Interaction{" +
			"interactionId=" + interactionId +
			", interactionUri=" + interactionUri +
			", interactionDate=" + interactionDate +
			", systemMessage='" + systemMessage + '\'' +
			", userMessage='" + userMessage + '\'' +
			", result='" + result + '\'' +
			", status=" + status +
			", errorMessage='" + errorMessage + '\'' +
			", causeErrorMessage='" + causeErrorMessage + '\'' +
			'}';
	}

	public static class Builder {
		private UUID interactionId;
		private URI interactionUri;
		private Instant interactionDate;
		private String systemMessage;
		private String userMessage;
		private String result;
		private Status status;
		private String errorMessage;
		private String causeErrorMessage;

		private Builder() {
		}

		private Builder(Interaction interaction) {
			this.interactionId = interaction.interactionId;
			this.interactionUri = interaction.interactionUri;
			this.interactionDate = interaction.interactionDate;
			this.systemMessage = interaction.systemMessage;
			this.userMessage = interaction.userMessage;
			this.result = interaction.result;
			this.status = interaction.status;
			this.errorMessage = interaction.errorMessage;
			this.causeErrorMessage = interaction.causeErrorMessage;
		}

		public Builder interactionId(UUID interactionId) {
			this.interactionId = interactionId;
			return this;
		}

		public Builder interactionUri(URI interactionUri) {
			this.interactionUri = interactionUri;
			return this;
		}

		public Builder interactionDate(Instant interactionDate) {
			this.interactionDate = interactionDate;
			return this;
		}

		public Builder systemMessage(String systemMessage) {
			this.systemMessage = systemMessage;
			return this;
		}

		public Builder userMessage(String userMessage) {
			this.userMessage = userMessage;
			return this;
		}

		public Builder result(String result) {
			this.result = result;
			return this;
		}

		public Builder status(Status status) {
			this.status = status;
			return this;
		}

		public Builder errorMessage(String errorMessage) {
			this.errorMessage = errorMessage;
			return this;
		}

		public Builder causeErrorMessage(String causeErrorMessage) {
			this.causeErrorMessage = causeErrorMessage;
			return this;
		}

		public Interaction build() {
			return new Interaction(this);
		}
	}
}
