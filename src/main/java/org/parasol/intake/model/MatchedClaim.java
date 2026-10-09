package org.parasol.intake.model;

import java.util.Objects;
import java.util.stream.Stream;

import org.parasol.intake.IntakeClaimStatus;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * The claim the intake matched in code before the agents run (claim number regex or reply headers, plus the sender
 * check), or {@link #none()} when nothing matched.
 * <p>
 * There's a {@code none()} value instead of {@code null}: the agentic scope treats a {@code null} input as missing, so
 * the router's conditions would throw {@code MissingArgumentException} on every unmatched email. Strings only, no
 * {@code Optional}, so Flow's checkpoint of the agentic scope can round-trip it.
 *
 * @param claimNumber the matched claim's number (e.g. {@code CLM01000000}), or {@code null} for {@link #none()}
 * @param status the matched claim's stored status (e.g. {@code Pending Information}), or {@code null} for
 * {@link #none()}
 */
public record MatchedClaim(String claimNumber, String status) {
	private static final MatchedClaim NONE = new MatchedClaim(null, null);

	public MatchedClaim {
		// Both or neither: a claim number without a status (or the reverse) can't be routed
		if ((claimNumber == null) != (status == null)) {
			throw new IllegalArgumentException("A matched claim needs both a claim number and a status, or neither");
		}
	}

	/**
	 * A matched claim.
	 *
	 * @param claimNumber the claim's number
	 * @param status the claim's stored status
	 * @return the matched claim
	 */
	public static MatchedClaim of(String claimNumber, String status) {
		return new MatchedClaim(Objects.requireNonNull(claimNumber, "claimNumber"), Objects.requireNonNull(status, "status"));
	}

	/**
	 * No claim matched.
	 *
	 * @return the value for an unmatched email
	 */
	public static MatchedClaim none() {
		return NONE;
	}

	/**
	 * Whether a claim matched.
	 *
	 * @return {@code true} unless this is {@link #none()}
	 */
	// Not a record component: kept out of the JSON so a checkpoint holds only the two strings
	@JsonIgnore
	public boolean isMatched() {
		return this.claimNumber != null;
	}

	/**
	 * Whether the matched claim still takes customer information: {@code Pending Information}, or {@code Pending Review}
	 * (a reply then supersedes the review, task 08).
	 *
	 * @return {@code true} for a matched claim in one of the two pending statuses
	 */
	@JsonIgnore
	public boolean isPending() {
		return Stream.of(IntakeClaimStatus.PENDING_INFORMATION, IntakeClaimStatus.PENDING_REVIEW)
			.map(IntakeClaimStatus::label)
			.anyMatch(label -> label.equalsIgnoreCase(this.status));
	}
}
