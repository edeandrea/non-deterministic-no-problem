package org.parasol.intake.review.rest;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * A claim's new sentiment, returned by {@code POST /api/db/claims/{id}/sentiment}. It's already stored on the
 * claim.
 *
 * @param sentiment the claimant's sentiment
 */
@JsonNaming(SnakeCaseStrategy.class)
public record SentimentAssessment(String sentiment) {
}
