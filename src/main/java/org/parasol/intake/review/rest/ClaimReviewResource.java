package org.parasol.intake.review.rest;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.UriInfo;

import org.jboss.resteasy.reactive.ResponseStatus;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimNotFoundException;
import org.parasol.intake.MissingItem;
import org.parasol.intake.model.ClaimIntakeState;
import org.parasol.intake.review.ClaimReviewService;
import org.parasol.intake.review.ClaimSentimentReassessor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

/**
 * A claim's review: the state a claims processor needs for the review panel, and the decision endpoint. A missing claim
 * surfaces as a {@link ClaimNotFoundException}, and every review-specific failure as one of the {@code org.parasol.intake.review}
 * domain exceptions; both are mapped to RFC 9457 Problem Details ({@link ReviewExceptionMappings}, and
 * {@code org.parasol.claim.rest.ClaimExceptionMappings} for the claim one).
 */
@Path("/api/db/claims/{id}")
@Produces(MediaType.APPLICATION_JSON)
class ClaimReviewResource {
	private final ClaimReviewService service;
	private final ClaimSentimentReassessor sentimentReassessor;

	ClaimReviewResource(ClaimReviewService service, ClaimSentimentReassessor sentimentReassessor) {
		this.service = service;
		this.sentimentReassessor = sentimentReassessor;
	}

	/**
	 * What a claims processor needs to decide a claim's review.
	 *
	 * @param claimId the claim id
	 * @return the review state
	 */
	@GET
	@Path("/review")
	public ClaimReviewState getReview(@PathParam("id") long claimId) {
		var claim = Claim.<Claim>findByIdOptional(claimId)
			.orElseThrow(() -> new ClaimNotFoundException(claimId));
		var state = ClaimIntakeState.findForClaim(claimId);

		return new ClaimReviewState(
			claim.status,
			state.map(s -> s.version).orElse(0L),
			state.map(this.service::isReadyForDecision).orElse(false),
			state.map(s -> Set.copyOf(s.requestedItems)).orElse(Set.of()),
			List.of(MissingItem.values()));
	}

	/**
	 * Submits a reviewer's decision, with the claim's fields as the reviewer confirmed or corrected them. Asynchronous:
	 * returns once the decision is published, not once the intake workflow has applied it (the workflow sets the reviewed
	 * values and the new status together). The body carries nothing to read back: the UI already re-fetches
	 * {@code GET /api/db/claims/{id}} afterward, since this call doesn't wait for the claim to actually change.
	 * <p>
	 *   Nothing is published unless the body is valid ({@code 400}), the claim exists ({@code 404}), its review is open at
	 *   the version the reviewer saw ({@code 409}), the reviewed values fit the claim ({@code 422}) and its run is waiting
	 *   ({@code 404} if it's gone, {@code 409} if it isn't waiting).
	 * </p>
	 *
	 * @param claimId the claim id
	 * @param request the decision
	 * @return {@code 202 Accepted}, with no body
	 */
	@POST
	@Path("/review-decision")
	@Consumes(MediaType.APPLICATION_JSON)
	@ResponseStatus(202)
	public void decide(@PathParam("id") long claimId, @Valid @NotNull ReviewDecisionRequest request) {
		this.service.submit(claimId, request.version(), request.toDecision());
	}

	/**
	 * Assesses the claimant's sentiment again, from the claim's whole correspondence, and stores it. Only while the claim's
	 * review is open: {@code 404} for an unknown claim, {@code 409} if the claim isn't {@code Pending Review}, a decision
	 * is in flight, or it changed since the reviewer's version (before or during the assessment), and {@code 502} if the
	 * model fails. The claim keeps its previous sentiment on any error.
	 *
	 * @param claimId the claim id
	 * @param request the version the reviewer saw
	 * @return the new sentiment
	 */
	@POST
	@Path("/sentiment")
	@Consumes(MediaType.APPLICATION_JSON)
	public SentimentAssessment assessSentiment(@PathParam("id") long claimId, @Valid @NotNull SentimentAssessmentRequest request) {
		return new SentimentAssessment(this.sentimentReassessor.reassess(claimId, request.version()));
	}

	// Here rather than in ReviewExceptionMappings, so they apply only to this resource's endpoints. Quarkus REST rethrows
	// a MismatchedInputException (and ships its own mapper for it), but wraps every other unreadable body in a bare 400
	@ServerExceptionMapper
	Response mapMismatchedInput(MismatchedInputException exception, UriInfo uriInfo) {
		return ReviewExceptionMappings.unreadableBody(exception, uriInfo);
	}

	@ServerExceptionMapper
	Response mapUnreadableBody(WebApplicationException exception, UriInfo uriInfo) {
		return Optional.ofNullable(exception.getCause())
			.filter(JsonProcessingException.class::isInstance)
			.map(JsonProcessingException.class::cast)
			.map(cause -> ReviewExceptionMappings.unreadableBody(cause, uriInfo))
			.orElseGet(exception::getResponse);
	}
}