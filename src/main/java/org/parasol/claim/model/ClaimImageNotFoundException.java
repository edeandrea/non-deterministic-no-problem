package org.parasol.claim.model;

/**
 * Thrown when a {@link ClaimImage} with the requested id doesn't exist for the requested claim. That covers both an
 * unknown image id and an image that belongs to a different claim; the two aren't distinguished, so image ids can't be
 * probed across claims.
 */
public class ClaimImageNotFoundException extends RuntimeException {
	private final long claimId;
	private final long imageId;

	/**
	 * Creates the exception for the claim and image ids that didn't match.
	 *
	 * @param claimId the id of the claim the image was requested for
	 * @param imageId the id of the requested image
	 */
	public ClaimImageNotFoundException(long claimId, long imageId) {
		super("Image %d was not found for claim %d".formatted(imageId, claimId));
		this.claimId = claimId;
		this.imageId = imageId;
	}

	/**
	 * The id of the claim the image was requested for.
	 *
	 * @return the claim id
	 */
	public long claimId() {
		return this.claimId;
	}

	/**
	 * The id of the requested image.
	 *
	 * @return the image id
	 */
	public long imageId() {
		return this.imageId;
	}
}