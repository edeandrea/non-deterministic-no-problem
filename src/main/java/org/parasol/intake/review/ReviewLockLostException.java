package org.parasol.intake.review;

/**
 * Thrown when a reviewer's decision loses the optimistic lock on a claim's review: the {@code version} they saw is stale,
 * either because another decision claimed the review first, or because a customer reply superseded it.
 */
public class ReviewLockLostException extends RuntimeException {
	private final long claimId;

	/**
	 * Creates the exception for the claim whose review changed under the reviewer.
	 *
	 * @param claimId the claim id
	 */
	public ReviewLockLostException(long claimId) {
		// An expected outcome that is mapped to a response: no stack trace is ever shown or logged, so don't compute one
		super("Claim %d's review changed before this decision could be claimed".formatted(claimId), null, false, false);
		this.claimId = claimId;
	}

	/**
	 * The id of the claim whose review changed.
	 *
	 * @return the claim id
	 */
	public long claimId() {
		return this.claimId;
	}
}
