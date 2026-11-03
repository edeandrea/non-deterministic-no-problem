package org.parasol.claim.rest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.parasol.claim.model.CorrespondenceDirection;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * One email of a claim's correspondence, returned by {@code GET /api/db/claims/{id}/correspondence}.
 *
 * @param id the row id
 * @param direction {@code INBOUND} for the customer's email, {@code OUTBOUND} for a reply the intake sent
 * @param sender the sender's address
 * @param subject the subject as sent or received; empty for an email recorded before subjects were kept
 * @param date when the mail server received (or, for a reply, accepted) the email
 * @param text the email's text, without the quoted earlier messages of the thread
 * @param createdClaim whether this email created the claim. If none does (an adjustor entered the claim), the claim's own subject,
 *            body and photos aren't in any message, and the UI shows them first
 * @param images the photos the email carried; empty if it carried none (and then left out of the JSON, like any empty value, so
 *            a client must treat a missing {@code images} as empty)
 */
@JsonNaming(SnakeCaseStrategy.class)
public record CorrespondenceMessage(
	long id,
	CorrespondenceDirection direction,
	String sender,
	String subject,
	Instant date,
	String text,
	boolean createdClaim,
	List<ClaimImageMetadata> images) {
	/**
	 * Reads a missing {@code images} (see above) as no images.
	 */
	public CorrespondenceMessage {
		images = Optional.ofNullable(images).orElseGet(List::of);
	}
}
