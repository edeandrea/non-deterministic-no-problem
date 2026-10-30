package org.parasol.intake.observability;

import java.util.Locale;

import org.parasol.intake.model.MatchedClaim;

/**
 * What kind of claim the intake matched in code before the classifier ran. The lower-case constant name is the {@code matched_claim}
 * tag of {@code claim.intake.classifications}.
 */
public enum MatchedClaimKind {
	/**
	 * No claim matched.
	 */
	NONE,

	/**
	 * A claim that still takes customer information ({@code Pending Information} or {@code Pending Review}) matched.
	 */
	PENDING,

	/**
	 * A claim in any other status matched.
	 */
	OTHER;

	public static MatchedClaimKind of(MatchedClaim matched) {
		return !matched.isMatched() ? NONE : (matched.isPending() ? PENDING : OTHER);
	}

	String tag() {
		return name().toLowerCase(Locale.ROOT);
	}
}
