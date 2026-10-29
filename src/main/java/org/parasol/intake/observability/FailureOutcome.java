package org.parasol.intake.observability;

import java.util.Locale;

/**
 * What a failure meant for the run. The lower-case constant name is the {@code outcome} tag of {@code claim.intake.failures}.
 */
public enum FailureOutcome {
	/**
	 * The run faulted and its email went the failure path.
	 */
	FATAL,

	/**
	 * The run carried on: a decision that couldn't be applied put the claim back in review for another, or the decision was
	 * applied and only its email couldn't be sent.
	 */
	RECOVERED;

	String tag() {
		return name().toLowerCase(Locale.ROOT);
	}
}
