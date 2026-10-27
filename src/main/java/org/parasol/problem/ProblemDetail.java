package org.parasol.problem;

import jakarta.ws.rs.core.Response.StatusType;
import jakarta.ws.rs.core.UriInfo;

/**
 * An RFC 9457 problem detail, used by every REST exception mapper in the app. The format is a named standard in its
 * own right, not generic REST infrastructure, so it gets its own package rather than a domain's or a technical-layer
 * bucket.
 *
 * @param type a URI identifying the problem type; {@code about:blank} means the HTTP status says it all
 * @param title the HTTP status reason phrase
 * @param status the HTTP status code
 * @param detail an explanation specific to this occurrence
 * @param instance the path of the request that failed
 */
public record ProblemDetail(String type, String title, int status, String detail, String instance) {
	/**
	 * The {@code application/problem+json} media type every problem detail response is sent with.
	 */
	public static final String MEDIA_TYPE = "application/problem+json";

	/**
	 * Builds a problem detail for the given status, with {@code type} left as {@code about:blank}.
	 *
	 * @param status the HTTP status
	 * @param detail an explanation specific to this occurrence
	 * @param uriInfo the failed request, for its path
	 * @return the problem detail
	 */
	public static ProblemDetail of(StatusType status, String detail, UriInfo uriInfo) {
		var instance = uriInfo.getRequestUri()
			.getRawPath();

		return new ProblemDetail("about:blank", status.getReasonPhrase(), status.getStatusCode(), detail, instance);
	}
}