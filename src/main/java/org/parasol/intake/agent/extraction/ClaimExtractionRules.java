package org.parasol.intake.agent.extraction;

import static java.util.function.Predicate.not;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.parasol.intake.MissingItem;
import org.parasol.intake.model.ClaimExtraction;
import org.parasol.intake.model.IncidentDetails;

/**
 * Pure functions over {@link ClaimExtraction} to decide completeness.
 */
public final class ClaimExtractionRules {
	private ClaimExtractionRules() {
	}

	/**
	 * Finds the items a claim is still missing after an extraction run.
	 * <p>
	 * A claim is missing an item if:
	 * <ul>
	 * <li>any core field (description, incident date, location, category) has no value; {@code OTHER} counts as a
	 * category, and an incident date that isn't a valid ISO date counts as no value</li>
	 * <li>a reviewer ticked the item, and the customer's newest reply did not answer it</li>
	 * <li>a reviewer ticked the item, and the newest reply is blank (it answers nothing, whatever the model says)</li>
	 * </ul>
	 * The incident time is optional and never missing.
	 *
	 * @param extraction the extraction result
	 * @param requestedByReviewer items ticked by a reviewer under Needs More Information, in any collection (duplicates
	 * collapse); empty if none
	 * @param replyIsBlank whether the newest reply's body (after quote stripping) is blank
	 * @return the missing items in {@link MissingItem} declaration order (the order the reply templates list them in),
	 * empty when the claim is complete
	 */
	public static Set<MissingItem> findMissingItems(
		ClaimExtraction extraction,
		Collection<MissingItem> requestedByReviewer,
		boolean replyIsBlank) {

		Objects.requireNonNull(extraction, "extraction");
		Objects.requireNonNull(requestedByReviewer, "requestedByReviewer");

		var details = extraction.details();
		var absent = Stream.of(MissingItem.values())
			.filter(item -> isAbsent(item, details));
		var unanswered = requestedByReviewer.stream()
			.filter(item -> replyIsBlank || !details.answeredItems().contains(item));

		// An EnumSet iterates in declaration order; Set.copyOf would not, and the reply templates list items in set order
		return Stream.concat(absent, unanswered)
			.collect(Collectors.collectingAndThen(
				Collectors.toCollection(() -> EnumSet.noneOf(MissingItem.class)),
				Collections::unmodifiableSet));
	}

	// Exhaustive on purpose: a new MissingItem won't compile until it says how its field counts as absent
	private static boolean isAbsent(MissingItem item, IncidentDetails details) {
		return switch (item) {
			case INCIDENT_DESCRIPTION -> isBlank(details.description());
			case INCIDENT_DATE -> IsoValues.parseDate(details.incidentDate()).isEmpty();
			case LOCATION -> isBlank(details.location());
			case CATEGORY -> (details.category() == null);
		};
	}

	private static boolean isBlank(String value) {
		return Stream.ofNullable(value)
			.noneMatch(not(String::isBlank));
	}
}
