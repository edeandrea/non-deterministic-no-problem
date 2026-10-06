package org.parasol.claim.rest;

import java.util.List;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.parasol.claim.model.Claim;

/**
 * Claims. Returns {@link ClaimDetails} DTOs rather than the entity. A missing claim surfaces as a
 * {@link org.parasol.claim.model.ClaimNotFoundException}, which {@link ClaimExceptionMappers} turns into an RFC 9457
 * Problem Details {@code 404}.
 */
@Produces(MediaType.APPLICATION_JSON)
@Path("/api/db/claims")
public class ClaimResource {
	private final ClaimMapper mapper;

	ClaimResource(ClaimMapper mapper) {
		this.mapper = mapper;
	}

	/**
	 * Lists all claims.
	 *
	 * @return every claim
	 */
	@GET
	public List<ClaimDetails> getall() {
		return this.mapper.toDetails(Claim.<Claim>listAll());
	}

	/**
	 * Returns one claim.
	 *
	 * @param id the claim id
	 * @return the claim
	 */
	@GET
	@Path("/{id}")
	public ClaimDetails getone(@PathParam("id") long id) {
		return this.mapper.toDetails(Claim.findExisting(id));
	}
}