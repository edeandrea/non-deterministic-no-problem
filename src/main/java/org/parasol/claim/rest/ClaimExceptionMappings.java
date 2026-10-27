package org.parasol.claim.rest;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.UriInfo;

import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.parasol.claim.model.ClaimImageNotFoundException;
import org.parasol.claim.model.ClaimNotFoundException;
import org.parasol.problem.ProblemDetail;

/**
 * Maps the claim domain's exceptions to RFC 9457 Problem Details ({@code application/problem+json}) for every endpoint.
 */
class ClaimExceptionMappings {
	@ServerExceptionMapper
	Response mapClaimNotFound(ClaimNotFoundException exception, UriInfo uriInfo) {
		return problem(Status.NOT_FOUND, exception.getMessage(), uriInfo);
	}

	@ServerExceptionMapper
	Response mapClaimImageNotFound(ClaimImageNotFoundException exception, UriInfo uriInfo) {
		return problem(Status.NOT_FOUND, exception.getMessage(), uriInfo);
	}

	private static Response problem(Status status, String detail, UriInfo uriInfo) {
		return Response.status(status)
			.type(ProblemDetail.MEDIA_TYPE)
			.entity(ProblemDetail.of(status, detail, uriInfo))
			.build();
	}
}