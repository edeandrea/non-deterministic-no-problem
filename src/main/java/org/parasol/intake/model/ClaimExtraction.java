package org.parasol.intake.model;

import java.util.Objects;

/**
 * The combined output of the claim extraction agents.
 *
 * @param summary A factual summary of the claim correspondence, at most 5000 characters
 * @param sentiment The claimant's sentiment, at most 5000 characters
 * @param details The extracted incident details
 */
public record ClaimExtraction(
	String summary,
	String sentiment,
	IncidentDetails details) {

	public ClaimExtraction {
		Objects.requireNonNull(summary, "summary");
		Objects.requireNonNull(sentiment, "sentiment");
		Objects.requireNonNull(details, "details");
	}
}
