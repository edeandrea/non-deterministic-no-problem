package org.parasol.intake.review;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * The data of the {@link ReviewDecisionPublisher#REVIEW_DECIDED} event that wakes a run waiting for its review.
 *
 * @param claimId The claim that was reviewed
 * @param runId The id of the intake run waiting for the review: the {@code reviewRunId} the reviewer's decision claimed
 * @param decision The decision
 */
public record ReviewDecided(long claimId, String runId, ReviewDecision decision) {
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
