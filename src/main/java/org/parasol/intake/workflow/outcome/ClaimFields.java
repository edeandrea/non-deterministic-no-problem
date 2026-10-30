package org.parasol.intake.workflow.outcome;

import static java.util.function.Predicate.not;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.parasol.claim.model.Claim;
import org.parasol.intake.MissingItem;
import org.parasol.intake.agent.extraction.IsoValues;
import org.parasol.intake.model.ClaimExtraction;
import org.parasol.intake.model.IncidentDetails;

/**
 * Moves incident details between a {@link Claim} and the agents' {@link IncidentDetails}.
 */
public final class ClaimFields {
	private ClaimFields() {
	}

	/**
	 * The fields a claim already has, as what the agents extracted so far.
	 * <p>
	 * A claim stores no incident-described flag, so it's derived. The summary can't tell, because the summary agent
	 * always writes one, even for "something happened, help". The requested items can: the customer was asked for the
	 * incident description exactly when it was missing (or a reviewer wanted more of it).
	 *
	 * @param claim the persisted claim
	 * @param requestedItems what the customer was last asked for; empty if nothing
	 * @return the details, with {@code incidentDescribed} {@code false} when the description was requested, otherwise
	 * {@code true} when the claim has a summary and {@code null} when it has none
	 */
	public static IncidentDetails extractedSoFar(Claim claim, Collection<MissingItem> requestedItems) {
		var described = requestedItems.contains(MissingItem.INCIDENT_DESCRIPTION) ?
		                Boolean.FALSE :
		                Optional.ofNullable(claim.summary)
			                .filter(not(String::isBlank))
			                .map(_ -> Boolean.TRUE)
			                .orElse(null);

		return new IncidentDetails(
			described,
			Optional.ofNullable(claim.incidentDate).map(Object::toString).orElse(null),
			Optional.ofNullable(claim.incidentTime).map(Object::toString).orElse(null),
			claim.location,
			claim.category,
			claim.policyNumber,
			Set.of());
	}

	/**
	 * Sets what an extraction found on a claim. A field the extraction lacks, or left blank, never overwrites a value the
	 * claim already has.
	 */
	static void merge(Claim claim, ClaimExtraction extraction) {
		var details = extraction.details();

		claim.summary = firstNonBlank(extraction.summary(), claim.summary);
		claim.sentiment = firstNonBlank(extraction.sentiment(), claim.sentiment);
		claim.location = firstNonBlank(details.location(), claim.location);
		claim.incidentDate = IsoValues.parseDate(details.incidentDate()).orElse(claim.incidentDate);
		claim.incidentTime = IsoValues.parseTime(details.incidentTime()).orElse(claim.incidentTime);
		claim.category = Optional.ofNullable(details.category()).orElse(claim.category);
	}

	private static String firstNonBlank(String preferred, String fallback) {
		return Stream.of(preferred, fallback)
			.filter(value -> (value != null) && !value.isBlank())
			.findFirst()
			.orElse(null);
	}
}
