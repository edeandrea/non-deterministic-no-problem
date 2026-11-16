package org.parasol.intake.model;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * The data of the {@link #REVIEW_DECIDED} event that wakes a run waiting for its review.
 *
 * @param claimId The claim that was reviewed
 * @param runId The id of the intake run waiting for the review: the {@code reviewRunId} the reviewer's decision claimed
 * @param decision The decision
 */
public record ReviewDecided(long claimId, String runId, ReviewDecision decision) {
	/**
	 * The CloudEvent type the intake run waits for.
	 */
	public static final String REVIEW_DECIDED = "org.parasol.intake.review.decided";

	public ReviewDecided {
		Objects.requireNonNull(runId, "runId");
		Objects.requireNonNull(decision, "decision");
	}

	/**
	 * Whether or not a decided review is ready
	 * @return True if {@link #decision()} is ready, false otherwise
	 */
	@JsonIgnore
	public boolean isReviewReady() {
		return this.decision.isReady();
	}
}
