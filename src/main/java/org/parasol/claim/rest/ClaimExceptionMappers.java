package org.parasol.claim.rest;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.UriInfo;

import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.parasol.claim.model.ClaimImageNotFoundException;
import org.parasol.claim.model.ClaimNotFoundException;

/**
 * Maps the claim domain's exceptions to RFC 9457 Problem Details ({@code application/problem+json}) for every endpoint.
 */
class ClaimExceptionMappers {
	static final String PROBLEM_JSON = "application/problem+json";

	@ServerExceptionMapper
	Response mapClaimNotFound(ClaimNotFoundException exception, UriInfo uriInfo) {
		return problem(Status.NOT_FOUND, exception.getMessage(), uriInfo);
	}

	@ServerExceptionMapper
	Response mapClaimImageNotFound(ClaimImageNotFoundException exception, UriInfo uriInfo) {
		return problem(Status.NOT_FOUND, exception.getMessage(), uriInfo);
	}

	private static Response problem(Status status, String detail, UriInfo uriInfo) {
		var problem = new ProblemDetail("about:blank", status.getReasonPhrase(), status.getStatusCode(), detail, uriInfo.getRequestUri().getRawPath());

		return Response.status(status)
			.type(PROBLEM_JSON)
			.entity(problem)
			.build();
	}

	/**
	 * An RFC 9457 problem detail.
	 *
	 * @param type a URI identifying the problem type; {@code about:blank} means the HTTP status says it all
	 * @param title the HTTP status reason phrase
	 * @param status the HTTP status code
	 * @param detail an explanation specific to this occurrence
	 * @param instance the path of the request that failed
	 */
	record ProblemDetail(String type, String title, int status, String detail, String instance) {
	}
}