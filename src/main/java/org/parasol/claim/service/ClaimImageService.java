package org.parasol.claim.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimImage;
import org.parasol.claim.model.ClaimImageKind;

/** Stores images associated with claims. */
@ApplicationScoped
public class ClaimImageService {
	/**
	 * Stores an image and flushes it so its generated id is available to the caller.
	 *
	 * @param claim the claim the image belongs to
	 * @param kind the image kind
	 * @param fileName the original file name
	 * @param contentType the media type to return when serving the image
	 * @param data the image bytes
	 * @return the persisted image
	 */
	@Transactional
	public ClaimImage storeImage(Claim claim, ClaimImageKind kind, String fileName, String contentType, byte[] data) {
		var image = new ClaimImage();
		image.claim = claim;
		image.kind = kind;
		image.fileName = fileName;
		image.contentType = contentType;
		image.data = data.clone();
		image.persistAndFlush();

		return image;
	}
}
