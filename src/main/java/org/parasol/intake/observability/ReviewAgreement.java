package org.parasol.intake.observability;

import java.util.Locale;

/**
 * Whether a reviewer accepted what the agents produced. The lower-case constant name is the {@code agreement} tag of
 * {@code claim.intake.review.overrides}.
 */
public enum ReviewAgreement {
	/**
	 * The reviewer marked the claim ready without changing a field.
	 */
	AGREED,

	/**
	 * The reviewer asked the customer for more, or changed a field.
	 */
	OVERRIDDEN;

	String tag() {
		return name().toLowerCase(Locale.ROOT);
	}
}
