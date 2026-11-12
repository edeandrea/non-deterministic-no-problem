package org.parasol.intake.review;

/**
 * Thrown when a claim's review run exists but isn't waiting for a decision, so there's nothing for the decision
 * to wake.
 */
public class ReviewRunNotWaitingException extends RuntimeException {
	private final long claimId;
	private final String runId;

	/**
	 * Creates the exception for the claim and run that isn't waiting.
	 *
	 * @param claimId the claim id
	 * @param runId the id of the run that isn't waiting
	 */
	public ReviewRunNotWaitingException(long claimId, String runId) {
		// An expected outcome that is mapped to a response: no stack trace is ever shown or logged, so don't compute one
		super("Claim %d's review run %s isn't waiting for a decision".formatted(claimId, runId), null, false, false);
		this.claimId = claimId;
		this.runId = runId;
	}

	/**
	 * The id of the claim whose review run isn't waiting.
	 *
	 * @return the claim id
	 */
	public long claimId() {
		return this.claimId;
	}

	/**
	 * The id of the run that isn't waiting.
	 *
	 * @return the run id
	 */
	public String runId() {
		return this.runId;
	}
}