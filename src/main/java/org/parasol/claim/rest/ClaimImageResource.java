package org.parasol.claim.rest;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import org.parasol.claim.persistence.ClaimImageRepository;

/** REST endpoints for claim image metadata and binary content. */
@Path("/api/db/claims/{id}/images")
public class ClaimImageResource {
	@Inject
	ClaimImageRepository claimImageRepository;

	/** Lists a claim's images without returning their binary content. */
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public Response listImages(@PathParam("id") long claimId, @Context UriInfo uriInfo) {
		var response = claimImageRepository.listForClaim(claimId)
			.map(images -> images.stream()
				.map(image -> new ClaimImageMetadata(
					image.id(),
					image.kind(),
					image.fileName(),
					image.contentType(),
					uriInfo.getAbsolutePathBuilder().path(Long.toString(image.id())).build().toString()
				))
				.toList())
			.<Response>map(images -> Response.ok(images).build())
			.orElseGet(() -> notFound(uriInfo, "Claim %d was not found".formatted(claimId)));

		return response;
	}

	/** Returns image bytes when the image belongs to the requested claim. */
	@GET
	@Path("/{imageId}")
	@Produces(MediaType.WILDCARD)
	public Response getImage(@PathParam("id") long claimId, @PathParam("imageId") long imageId, @Context UriInfo uriInfo) {
		var image = claimImageRepository.findContentForClaim(claimId, imageId);

		var response = image
			.map(foundImage -> Response.ok(foundImage.data())
				.type(foundImage.contentType())
				.header("X-Content-Type-Options", "nosniff")
				.build())
			.orElseGet(() -> notFound(uriInfo, "Image %d was not found for claim %d".formatted(imageId, claimId)));

		return response;
	}

	private static Response notFound(UriInfo uriInfo, String detail) {
		return Response.status(Response.Status.NOT_FOUND)
			.type("application/problem+json")
			.entity(new ProblemDetail("about:blank", "Not Found", Response.Status.NOT_FOUND.getStatusCode(), detail, uriInfo.getPath()))
			.build();
	}

	private record ProblemDetail(String type, String title, int status, String detail, String instance) {
	}
}
