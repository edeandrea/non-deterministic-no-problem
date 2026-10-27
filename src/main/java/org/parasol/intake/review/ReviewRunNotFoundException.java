package org.parasol.intake.review;

/**
 * Thrown when a claim's review run no longer exists: the reviewer claimed the review, but the Flow instance it was
 * waiting on is gone.
 */
public class ReviewRunNotFoundException extends RuntimeException {
	private final long claimId;
	private final String runId;

	/**
	 * Creates the exception for the claim and run that no longer exists.
	 *
	 * @param claimId the claim id
	 * @param runId the id of the run that no longer exists
	 */
	public ReviewRunNotFoundException(long claimId, String runId) {
		// An expected outcome that is mapped to a response: no stack trace is ever shown or logged, so don't compute one
		super("Claim %d's review run %s no longer exists".formatted(claimId, runId), null, false, false);
		this.claimId = claimId;
		this.runId = runId;
	}

	/**
	 * The id of the claim whose review run is missing.
	 *
	 * @return the claim id
	 */
	public long claimId() {
		return this.claimId;
	}

	/**
	 * The id of the run that no longer exists.
	 *
	 * @return the run id
	 */
	public String runId() {
		return this.runId;
	}
}
