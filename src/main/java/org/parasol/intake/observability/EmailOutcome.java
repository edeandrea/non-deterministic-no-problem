package org.parasol.intake.observability;

import java.util.Locale;

/**
 * What finally became of an inbound email, counted once when it is filed (or skipped, found to be a duplicate, or failed). The lower-case
 * constant name is the {@code outcome} tag of {@code claim.intake.emails}.
 */
public enum EmailOutcome {
	NEW_CLAIM,
	CLAIM_UPDATE,
	STATUS_QUESTION,
	NOT_A_CLAIM,
	NO_MATCHING_CLAIM,
	NEEDS_CLARIFICATION,
	POLICY_REJECTED,
	SKIPPED_AUTO_REPLY,
	DUPLICATE,
	FAILED;

	String tag() {
		return name().toLowerCase(Locale.ROOT);
	}
}
