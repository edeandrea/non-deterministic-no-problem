package org.parasol.intake.review;

/**
 * Thrown when a claim's sentiment couldn't be assessed again: the model failed, or gave no assessment. The claim keeps
 * its previous sentiment.
 */
public class SentimentAssessmentFailedException extends RuntimeException {
	private final long claimId;

	/**
	 * Creates the exception for a claim whose sentiment couldn't be assessed.
	 *
	 * @param claimId the claim id
	 * @param cause what failed, if anything was thrown
	 */
	public SentimentAssessmentFailedException(long claimId, Throwable cause) {
		// An expected outcome that is mapped to a response: no stack trace is ever shown or logged, so don't compute one
		super("The sentiment of claim %d couldn't be assessed; it keeps its previous sentiment".formatted(claimId), cause, false, false);
		this.claimId = claimId;
	}

	/**
	 * The id of the claim whose sentiment couldn't be assessed.
	 *
	 * @return the claim id
	 */
	public long claimId() {
		return this.claimId;
	}
}
