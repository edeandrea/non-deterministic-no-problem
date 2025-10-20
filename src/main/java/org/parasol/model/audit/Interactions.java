package org.parasol.model.audit;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.quarkus.runtime.annotations.RegisterForReflection;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonProperty.Access;

@RegisterForReflection
public record Interactions(AuditDates auditDates, List<Interaction> interactions) {
	public static Interactions empty(AuditDates auditDates) {
		return new Interactions(auditDates, List.of());
	}

	public record Interaction(
		UUID interactionId,
		Instant interactionDate,
		String systemMessage,
		String userMessage,
		String result,
		String errorMessage,
		String causeErrorMessage
	) {
		public enum InteractionStatus { SUCCESS, FAILURE, UNKNOWN }

		public static Interaction success(UUID interactionId, Instant interactionDate, String systemMessage, String userMessage, String result) {
			return new Interaction(interactionId, interactionDate, systemMessage, userMessage, result, null, null);
		}

		public static Interaction failure(UUID interactionId, Instant interactionDate, String systemMessage, String userMessage, String errorMessage, String causeErrorMessage) {
			return new Interaction(interactionId, interactionDate, systemMessage, userMessage, null, errorMessage, causeErrorMessage);
		}

		@JsonProperty(value = "status", access = Access.READ_ONLY)
		public InteractionStatus getStatus() {
			return Optional.ofNullable(errorMessage)
				.map(String::strip)
				.filter(e -> !e.isEmpty())
				.map(e -> InteractionStatus.FAILURE)
				.orElse(InteractionStatus.SUCCESS);
		}

		@JsonProperty(value = "isFailure", access = Access.READ_ONLY)
		public boolean isFailure() {
			return getStatus() == InteractionStatus.FAILURE;
		}

		@JsonProperty(value = "isSuccess", access = Access.READ_ONLY)
		public boolean isSuccess() {
			return getStatus() == InteractionStatus.SUCCESS;
		}
	}
}
