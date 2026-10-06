package org.parasol.claim.rest;

import java.util.List;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.parasol.claim.model.ClaimImage;
import org.parasol.claim.model.ClaimImageNotFoundException;

/**
 * Claim images: metadata and image bytes. Missing claims and images surface as domain exceptions that
 * {@link ClaimExceptionMappings} turns into RFC 9457 Problem Details.
 */
@Path("/api/db/claims/{id}/images")
public class ClaimImageResource {
	private final ClaimMapper mapper;

	ClaimImageResource(ClaimMapper mapper) {
		this.mapper = mapper;
	}

	/**
	 * Lists a claim's images, oldest first, without their bytes.
	 *
	 * @param claimId the claim id
	 * @return the image metadata; empty if the claim has no images
	 */
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public List<ClaimImageMetadata> listImages(@PathParam("id") long claimId) {
		return this.mapper.toMetadata(ClaimImage.listForClaim(claimId), claimId);
	}

	/**
	 * Returns an image's bytes with its stored content type.
	 *
	 * @param claimId the claim id
	 * @param imageId the image id
	 * @return the image bytes
	 */
	@GET
	@Path("/{imageId}")
	@Produces(MediaType.WILDCARD)
	public Response getImage(@PathParam("id") long claimId, @PathParam("imageId") long imageId) {
		return ClaimImage.findForClaim(claimId, imageId)
			.map(image -> Response.ok(image.data, image.contentType.mediaType())
				.header("X-Content-Type-Options", "nosniff")
				.header(HttpHeaders.CONTENT_DISPOSITION, "inline")
				.build())
			.orElseThrow(() -> new ClaimImageNotFoundException(claimId, imageId));
	}
}