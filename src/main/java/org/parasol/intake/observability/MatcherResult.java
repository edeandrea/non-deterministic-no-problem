package org.parasol.intake.observability;

import java.util.Locale;

/**
 * What the intake's claim matching decided for an email. The lower-case constant name is the {@code result} tag of
 * {@code claim.intake.matcher.result}.
 */
public enum MatcherResult {
	/**
	 * The claim number or reply headers named a claim, and the sender is its customer.
	 */
	MATCHED,

	/**
	 * The email names a claim that isn't the sender's.
	 */
	WRONG_SENDER,

	/**
	 * No claim matched, and the sender has no pending claim for the resolver to choose from.
	 */
	NO_MATCH,

	/**
	 * The resolver chose one of the sender's pending claims.
	 */
	RESOLVED_EXISTING,

	/**
	 * The resolver found the email to be about a new incident.
	 */
	RESOLVED_NEW_INCIDENT,

	/**
	 * The resolver couldn't tell which pending claim the email is about.
	 */
	UNSURE;

	String tag() {
		return name().toLowerCase(Locale.ROOT);
	}
}
