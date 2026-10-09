package org.parasol.intake.model;

import java.util.Collection;
import java.util.Objects;

import dev.langchain4j.model.output.structured.Description;

/**
 * {@code ClaimResolver}'s answer: which of the sender's pending claims an unmatched email is about.
 * <p>
 * The {@code kind} {@link Description} is the resolver's instruction for each answer. LangChain4j appends this record's
 * JSON format to the request with the field descriptions and the constant names ({@code PojoOutputParser}), but not a
 * description per enum constant, so the meanings sit on the field, next to the enum. {@code ClaimResolutionTests}
 * checks the description covers every {@link Kind}.
 *
 * @param kind the answer
 * @param claimNumber the claim for {@link Kind#EXISTING}; otherwise ignored
 */
public record ClaimResolution(
	@Description("EXISTING if the email is clearly about one of the claims listed, NEW_INCIDENT if it describes a different incident, UNSURE if you can't tell")
	Kind kind,

	@Description("the claim number, only for EXISTING; null otherwise")
	String claimNumber) {

	/**
	 * What the resolver decided.
	 */
	public enum Kind {
		/** One of the offered claims. */
		EXISTING,
		/** A new incident: continue unmatched. */
		NEW_INCIDENT,
		/** Can't tell: ask the customer which claim. */
		UNSURE
	}

	public ClaimResolution {
		Objects.requireNonNull(kind, "kind");
	}

	/**
	 * Accepts {@link Kind#EXISTING} only for a claim the resolver was offered, so a claim number the model invents (or
	 * that the email talked it into) can never route the email to someone else's claim.
	 *
	 * @param offeredClaimNumbers the claim numbers the resolver was given
	 * @return this resolution, or {@link Kind#UNSURE} for an existing claim that wasn't offered
	 */
	public ClaimResolution limitToOfferedClaims(Collection<String> offeredClaimNumbers) {
		var isUnofferedClaim = (this.kind == Kind.EXISTING) && !offeredClaimNumbers.contains(this.claimNumber);
		return isUnofferedClaim ? new ClaimResolution(Kind.UNSURE, null) : this;
	}
}
