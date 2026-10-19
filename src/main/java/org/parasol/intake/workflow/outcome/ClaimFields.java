package org.parasol.intake.workflow.outcome;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.parasol.claim.model.Claim;
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
	 * The fields a claim already has, as what the agents extracted so far. A claim stores no description, so its summary
	 * stands in for it.
	 */
	public static IncidentDetails extractedSoFar(Claim claim) {
		return new IncidentDetails(
			claim.summary,
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
