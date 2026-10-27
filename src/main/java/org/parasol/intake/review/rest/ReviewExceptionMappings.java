package org.parasol.intake.review.rest;

import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.validation.Path.Node;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.Response.Status.Family;
import jakarta.ws.rs.core.Response.StatusType;
import jakarta.ws.rs.core.UriInfo;

import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.parasol.intake.review.ReviewLockLostException;
import org.parasol.intake.review.ReviewNotOpenException;
import org.parasol.intake.review.ReviewRunNotFoundException;
import org.parasol.intake.review.ReviewRunNotWaitingException;
import org.parasol.intake.review.ReviewedFieldsRejectedException;
import org.parasol.intake.review.SentimentAssessmentFailedException;
import org.parasol.problem.ProblemDetail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;

/**
 * Maps the review domain's exceptions, and this resource's Bean Validation failures, to RFC 9457 Problem Details
 * ({@code application/problem+json}): {@code 404}, {@code 409}, {@code 422} for reviewed values that don't fit the claim,
 * {@code 502} when the model fails, and {@code 400} for a body that isn't valid ({@link ClaimReviewResource} maps a body
 * that can't be read, through {@link #unreadableBody}). A claim that doesn't exist surfaces as {@code ClaimNotFoundException}, mapped by
 * {@code org.parasol.claim.rest.ClaimExceptionMappings}.
 */
class ReviewExceptionMappings {
	// RFC 9110 §15.5.21; Jakarta REST 4.0's Status has no constant for it
	private static final StatusType UNPROCESSABLE_CONTENT = new StatusType() {
		@Override
		public int getStatusCode() {
			return 422;
		}

		@Override
		public Family getFamily() {
			return Family.CLIENT_ERROR;
		}

		@Override
		public String getReasonPhrase() {
			return "Unprocessable Content";
		}
	};

	@ServerExceptionMapper
	Response mapReviewNotOpen(ReviewNotOpenException exception, UriInfo uriInfo) {
		return problem(Status.CONFLICT, exception.getMessage(), uriInfo);
	}

	@ServerExceptionMapper
	Response mapReviewLockLost(ReviewLockLostException exception, UriInfo uriInfo) {
		return problem(Status.CONFLICT, exception.getMessage(), uriInfo);
	}

	@ServerExceptionMapper
	Response mapReviewRunNotFound(ReviewRunNotFoundException exception, UriInfo uriInfo) {
		return problem(Status.NOT_FOUND, exception.getMessage(), uriInfo);
	}

	@ServerExceptionMapper
	Response mapReviewRunNotWaiting(ReviewRunNotWaitingException exception, UriInfo uriInfo) {
		return problem(Status.CONFLICT, exception.getMessage(), uriInfo);
	}

	@ServerExceptionMapper
	Response mapReviewedFieldsRejected(ReviewedFieldsRejectedException exception, UriInfo uriInfo) {
		return problem(UNPROCESSABLE_CONTENT, exception.getMessage(), uriInfo);
	}

	@ServerExceptionMapper
	Response mapSentimentAssessmentFailed(SentimentAssessmentFailedException exception, UriInfo uriInfo) {
		return problem(Status.BAD_GATEWAY, exception.getMessage(), uriInfo);
	}

	@ServerExceptionMapper
	Response mapConstraintViolation(ConstraintViolationException exception, UriInfo uriInfo) {
		var detail = exception.getConstraintViolations().stream()
			.map(ReviewExceptionMappings::describe)
			.collect(Collectors.joining("; "));

		return problem(Status.BAD_REQUEST, detail, uriInfo);
	}

	// The path starts with the resource method and its parameter ("decide.request.outcome"), which mean nothing to a
	// client. A class-level violation has nothing after them: its message already names what it's about. The rest is
	// named as the request names it (snake_case, like the DTOs' @JsonNaming), not as the Java fields are
	private static String describe(ConstraintViolation<?> violation) {
		var property = StreamSupport.stream(violation.getPropertyPath().spliterator(), false)
			.filter(node -> node.getKind() != ElementKind.METHOD)
			.filter(node -> node.getKind() != ElementKind.PARAMETER)
			.map(node -> PropertyNamingStrategies.SNAKE_CASE.nameForField(null, null, node.toString()))
			.collect(Collectors.joining("."));

		return property.isEmpty() ? violation.getMessage() : "%s %s".formatted(property, violation.getMessage());
	}

	// A body Jackson can't read (malformed JSON, a bad date, an unknown category or missing item) never reaches Bean
	// Validation. The original message leaves out the parser's location and source excerpt; when a type's own factory
	// rejected the value (an unknown category), its message says why
	static Response unreadableBody(JsonProcessingException exception, UriInfo uriInfo) {
		var detail = Optional.of(exception)
			.filter(ValueInstantiationException.class::isInstance)
			.map(Throwable::getCause)
			.map(Throwable::getMessage)
			.orElseGet(exception::getOriginalMessage);

		return problem(Status.BAD_REQUEST, detail, uriInfo);
	}

	private static Response problem(StatusType status, String detail, UriInfo uriInfo) {
		return Response.status(status)
			.type(ProblemDetail.MEDIA_TYPE)
			.entity(ProblemDetail.of(status, detail, uriInfo))
			.build();
	}
}