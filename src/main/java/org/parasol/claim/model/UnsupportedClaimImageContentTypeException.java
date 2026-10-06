package org.parasol.claim.model;

/**
 * Thrown when an image's media type isn't one of the allowed {@link ClaimImageContentType}s.
 */
public class UnsupportedClaimImageContentTypeException extends IllegalArgumentException {
	/**
	 * Creates the exception for the media type that was rejected.
	 *
	 * @param mediaType the rejected media type (may be {@code null})
	 */
	public UnsupportedClaimImageContentTypeException(String mediaType) {
		super("Unsupported claim image content type: %s".formatted(mediaType));
	}
}