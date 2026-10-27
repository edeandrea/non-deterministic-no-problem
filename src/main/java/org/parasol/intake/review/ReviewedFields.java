package org.parasol.intake.review;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimCategory;
import org.parasol.intake.MissingItem;

/**
 * The claim's fields as a reviewer confirmed or corrected them. A {@code null} field is one the reviewer didn't vouch for
 * (they asked the customer for it instead), so it leaves the claim's value as it is.
 *
 * @param description the incident description, stored as the claim's summary (a claim has no description of its own)
 * @param incidentDate the incident date
 * @param incidentTime the incident time; part of the date's field, so it's only applied along with a date, and a
 *                     {@code null} time then clears it
 * @param location the incident location
 * @param category the claim category
 */
public record ReviewedFields(String description, LocalDate incidentDate, LocalTime incidentTime, String location, ClaimCategory category) {
	private static final ReviewedFields NONE = new ReviewedFields(null, null, null, null, null);

	/**
	 * No reviewed fields: the decision leaves every field of the claim as it is.
	 *
	 * @return the empty fields
	 */
	public static ReviewedFields none() {
		return NONE;
	}

	/**
	 * Whether the reviewer gave a value for an item.
	 *
	 * @param item the item
	 * @return {@code true} if the item's field has a value
	 */
	// Exhaustive on purpose: a new MissingItem won't compile until it says which field it is
	public boolean hasValueFor(MissingItem item) {
		return switch (item) {
			case INCIDENT_DESCRIPTION -> (this.description != null);
			case INCIDENT_DATE -> (this.incidentDate != null);
			case LOCATION -> (this.location != null);
			case CATEGORY -> (this.category != null);
		};
	}

	/**
	 * Sets every field the reviewer gave a value for on a claim.
	 *
	 * @param claim the claim being decided
	 */
	public void applyTo(Claim claim) {
		claim.summary = Optional.ofNullable(this.description).orElse(claim.summary);
		claim.location = Optional.ofNullable(this.location).orElse(claim.location);
		claim.category = Optional.ofNullable(this.category).orElse(claim.category);

		if (this.incidentDate != null) {
			claim.incidentDate = this.incidentDate;
			claim.incidentTime = this.incidentTime;
		}
	}
}
