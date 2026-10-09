package org.parasol.intake.model;

import java.util.Objects;
import java.util.Set;

import org.parasol.intake.MissingItem;

/**
 * One of the sender's pending claims, as offered to {@code ClaimResolver}.
 *
 * @param claimNumber the claim's number
 * @param summary a short summary of the claim
 * @param requestedItems what we last asked the customer for; empty if nothing
 */
public record PendingClaim(String claimNumber, String summary, Set<MissingItem> requestedItems) {
	public PendingClaim {
		Objects.requireNonNull(claimNumber, "claimNumber");
		requestedItems = (requestedItems == null) ? Set.of() : Set.copyOf(requestedItems);
	}
}
