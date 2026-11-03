package org.parasol.intake.model;

import java.util.Objects;

/**
 * What {@code ClaimsMailboxAgent} returns: the outcome, and the label the classifier gave the email. The label only routes an email
 * that matched no claim, so it isn't part of the outcome, but the intake counts it.
 *
 * @param emailType the classifier's label
 * @param outcome what the agents decided
 */
public record ClassifiedOutcome(EmailType emailType, IntakeOutcome outcome) {
	public ClassifiedOutcome {
		Objects.requireNonNull(emailType, "emailType");
		Objects.requireNonNull(outcome, "outcome");
	}
}
