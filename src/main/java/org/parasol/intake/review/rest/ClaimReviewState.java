package org.parasol.intake.review.rest;

import java.util.List;
import java.util.Set;

import org.parasol.intake.MissingItem;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * What a claims processor needs to review a claim, returned by {@code GET /api/db/claims/{id}/review}. Kept off
 * {@code ClaimDetails}: the intake's bookkeeping (the {@code ClaimIntakeState} version and requested items) is
 * deliberately not part of the claim API ({@code ClaimRetrievalHasNoIntakeBookkeepingTests}).
 *
 * @param status the claim's current status
 * @param version the {@code ClaimIntakeState} version; send it back in the decision so a race with a customer reply or
 *                another decision can be detected
 * @param open whether a decision would actually succeed right now: the claim is {@code Pending Review}, no other
 *             decision is already in flight, and — checked live against the workflow, not just this bookkeeping —
 *             the run has actually reached its wait and is {@code WAITING} ({@link org.parasol.intake.review.ClaimReviewService#isReadyForDecision})
 * @param requestedItems the items already requested of the customer, if any
 * @param availableItems every {@link MissingItem}, for the UI's checklist
 */
@JsonNaming(SnakeCaseStrategy.class)
public record ClaimReviewState(String status, long version, boolean open, Set<MissingItem> requestedItems, List<MissingItem> availableItems) {
}
