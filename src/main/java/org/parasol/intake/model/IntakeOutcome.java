package org.parasol.intake.model;


import java.util.Objects;

import org.parasol.intake.model.IntakeOutcome.NewClaim;
import org.parasol.intake.model.IntakeOutcome.NoMatchingClaim;
import org.parasol.intake.model.IntakeOutcome.NotAClaim;
import org.parasol.intake.model.IntakeOutcome.PendingClaimUpdate;
import org.parasol.intake.model.IntakeOutcome.StatusReply;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * What {@code ClaimsMailboxAgent} decided for an email. The agents only decide: the intake workflow's steps (task 08)
 * persist, reply and file.
 * <p>
 * Jackson-polymorphic, because Flow persists step data with the Quarkus {@code ObjectMapper}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
	@JsonSubTypes.Type(value = NewClaim.class, name = "newClaim"),
	@JsonSubTypes.Type(value = PendingClaimUpdate.class, name = "pendingClaimUpdate"),
	@JsonSubTypes.Type(value = StatusReply.class, name = "statusReply"),
	@JsonSubTypes.Type(value = NotAClaim.class, name = "notAClaim"),
	@JsonSubTypes.Type(value = NoMatchingClaim.class, name = "noMatchingClaim")
})
public sealed interface IntakeOutcome {
	/**
	 * An unmatched email about a new incident: create a claim from the extraction.
	 *
	 * @param extraction the extracted details, summary and sentiment
	 */
	record NewClaim(ClaimExtraction extraction) implements IntakeOutcome {
		public NewClaim {
			Objects.requireNonNull(extraction, "extraction");
		}
	}

	/**
	 * A reply on a matched pending claim: merge the extraction into it.
	 *
	 * @param extraction the extraction over the claim's whole correspondence
	 */
	record PendingClaimUpdate(ClaimExtraction extraction) implements IntakeOutcome {
		public PendingClaimUpdate {
			Objects.requireNonNull(extraction, "extraction");
		}
	}

	/**
	 * An email on a matched claim in any other status: send the AI-written answer as plain text. The claim is never
	 * changed.
	 *
	 * @param statusAnswer the reply body
	 */
	record StatusReply(String statusAnswer) implements IntakeOutcome {
		public StatusReply {
			Objects.requireNonNull(statusAnswer, "statusAnswer");
		}
	}

	/**
	 * An unmatched email that isn't about a claim.
	 */
	record NotAClaim() implements IntakeOutcome {
	}

	/**
	 * An unmatched email that reads as a follow-up: the one "no matching claim" reply, nothing changed.
	 */
	record NoMatchingClaim() implements IntakeOutcome {
	}
}
