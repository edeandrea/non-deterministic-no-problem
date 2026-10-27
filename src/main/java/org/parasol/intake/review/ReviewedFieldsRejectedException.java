package org.parasol.intake.review;

/**
 * Thrown when a reviewer's values are well-formed but don't fit the claim they're for, e.g. an incident date before the
 * policy's inception date.
 */
public class ReviewedFieldsRejectedException extends RuntimeException {
	private final long claimId;

	/**
	 * Creates the exception for a claim whose reviewed values were rejected.
	 *
	 * @param claimId the claim id
	 * @param reason why the values were rejected
	 */
	public ReviewedFieldsRejectedException(long claimId, String reason) {
		// An expected outcome that is mapped to a response: no stack trace is ever shown or logged, so don't compute one
		super("The reviewed values for claim %d were rejected: %s".formatted(claimId, reason), null, false, false);
		this.claimId = claimId;
	}

	/**
	 * The id of the claim whose reviewed values were rejected.
	 *
	 * @return the claim id
	 */
	public long claimId() {
		return this.claimId;
	}
}
