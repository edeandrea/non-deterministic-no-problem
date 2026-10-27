package org.parasol.intake.review.rest;

import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * The body of {@code POST /api/db/claims/{id}/sentiment}.
 *
 * @param version the {@code ClaimReviewState} version the reviewer saw; required, so a client can't skip the lock by
 *                leaving it out
 */
@JsonNaming(SnakeCaseStrategy.class)
public record SentimentAssessmentRequest(@NotNull Long version) {
}
