package org.parasol.intake.review;

/**
 * Thrown when a reviewer tries to decide a claim that has no review open to decide: the claim isn't {@code Pending
 * Review}, its run isn't waiting, or another decision already claimed it.
 */
public class ReviewNotOpenException extends RuntimeException {
	private final long claimId;

	/**
	 * Creates the exception for the claim whose review isn't open.
	 *
	 * @param claimId the claim id
	 */
	public ReviewNotOpenException(long claimId) {
		// An expected outcome that is mapped to a response: no stack trace is ever shown or logged, so don't compute one
		super("Claim %d has no review open to decide".formatted(claimId), null, false, false);
		this.claimId = claimId;
	}

	/**
	 * The id of the claim whose review isn't open.
	 *
	 * @return the claim id
	 */
	public long claimId() {
		return this.claimId;
	}
}
