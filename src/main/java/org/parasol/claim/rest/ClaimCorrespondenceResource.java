package org.parasol.claim.rest;

import java.util.List;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.parasol.claim.model.ClaimCorrespondence;
import org.parasol.claim.model.ClaimImage;

/**
 * A claim's email correspondence. A missing claim surfaces as a domain exception that {@link ClaimExceptionMappings} turns
 * into RFC 9457 Problem Details.
 */
@Path("/api/db/claims/{id}/correspondence")
class ClaimCorrespondenceResource {
	private final ClaimMapper mapper;

	ClaimCorrespondenceResource(ClaimMapper mapper) {
		this.mapper = mapper;
	}

	/**
	 * Lists a claim's emails in the order they happened: the customer's and the replies the intake sent, each with the photos
	 * it carried.
	 *
	 * @param claimId the claim id
	 * @return the messages; empty if the claim has no recorded correspondence (e.g. a seeded claim)
	 */
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public List<CorrespondenceMessage> listCorrespondence(@PathParam("id") long claimId) {
		return this.mapper.toMessages(ClaimCorrespondence.listForClaim(claimId), ClaimImage.listForClaim(claimId), claimId);
	}
}
