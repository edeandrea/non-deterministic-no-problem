package org.parasol.claim.model;

/**
 * Thrown when no {@link Claim} exists with the requested id.
 */
public class ClaimNotFoundException extends RuntimeException {
	private final long claimId;

	/**
	 * Creates the exception for the claim id that wasn't found.
	 *
	 * @param claimId the id of the missing claim
	 */
	public ClaimNotFoundException(long claimId) {
		super("Claim %d was not found".formatted(claimId));
		this.claimId = claimId;
	}

	/**
	 * The id of the claim that wasn't found.
	 *
	 * @return the claim id
	 */
	public long claimId() {
		return this.claimId;
	}
}