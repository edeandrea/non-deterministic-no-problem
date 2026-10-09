package org.parasol.intake.agent.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.parasol.claim.model.ClaimCategory;
import org.parasol.intake.MissingItem;
import org.parasol.intake.model.ClaimExtraction;
import org.parasol.intake.model.IncidentDetails;

class ClaimExtractionRulesTests {
	private static final IncidentDetails COMPLETE = new IncidentDetails(
		"car hit a tree",
		"2026-10-07",
		"14:30",
		"Elm Street",
		ClaimCategory.SINGLE_VEHICLE,
		"AC-1234567",
		Set.of()
	);

	@Test
	void completeClaimMissesNothing() {
		assertThat(ClaimExtractionRules.findMissingItems(extraction(COMPLETE), Set.of(), false))
			.isEmpty();
	}

	static Stream<Arguments> absentCoreFields() {
		return Stream.of(
			Arguments.of("null description", withDescription(null), MissingItem.INCIDENT_DESCRIPTION),
			Arguments.of("blank description", withDescription("  "), MissingItem.INCIDENT_DESCRIPTION),
			Arguments.of("null date", withDate(null), MissingItem.INCIDENT_DATE),
			Arguments.of("invalid ISO date", withDate("2026-13-40"), MissingItem.INCIDENT_DATE),
			Arguments.of("null location", withLocation(null), MissingItem.LOCATION),
			Arguments.of("blank location", withLocation(""), MissingItem.LOCATION),
			Arguments.of("null category", withCategory(null), MissingItem.CATEGORY)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("absentCoreFields")
	void anAbsentCoreFieldIsMissing(String description, IncidentDetails details, MissingItem expected) {
		assertThat(ClaimExtractionRules.findMissingItems(extraction(details), Set.of(), false))
			.containsExactly(expected);
	}

	@Test
	void otherCategoryIsNotMissing() {
		assertThat(ClaimExtractionRules.findMissingItems(extraction(withCategory(ClaimCategory.OTHER)), Set.of(), false))
			.isEmpty();
	}

	@Test
	void incidentTimeIsNeverMissing() {
		var noTime = new IncidentDetails(
			COMPLETE.description(),
			COMPLETE.incidentDate(),
			null,
			COMPLETE.location(),
			COMPLETE.category(),
			COMPLETE.policyNumber(),
			Set.of()
		);

		assertThat(ClaimExtractionRules.findMissingItems(extraction(noTime), Set.of(), false))
			.isEmpty();
	}

	@Test
	void reviewerTickedItemsAnsweredByTheReplyAreNotMissing() {
		var answered = withAnswered(Set.of(MissingItem.LOCATION, MissingItem.INCIDENT_DATE));

		assertThat(ClaimExtractionRules.findMissingItems(
			extraction(answered),
			Set.of(MissingItem.LOCATION, MissingItem.INCIDENT_DATE),
			false))
			.isEmpty();
	}

	@Test
	void reviewerTickedItemsStayMissingUntilAnsweredEvenWhenTheFieldHasAValue() {
		var partlyAnswered = withAnswered(Set.of(MissingItem.LOCATION));

		assertThat(ClaimExtractionRules.findMissingItems(
			extraction(partlyAnswered),
			Set.of(MissingItem.LOCATION, MissingItem.INCIDENT_DATE),
			false))
			.containsExactly(MissingItem.INCIDENT_DATE);
	}

	@Test
	void blankReplyKeepsEveryTickedItemMissingWhateverTheModelSays() {
		var claimedAnswered = withAnswered(Set.of(MissingItem.INCIDENT_DESCRIPTION, MissingItem.LOCATION));

		assertThat(ClaimExtractionRules.findMissingItems(
			extraction(claimedAnswered),
			Set.of(MissingItem.INCIDENT_DESCRIPTION, MissingItem.LOCATION),
			true))
			.containsExactly(MissingItem.INCIDENT_DESCRIPTION, MissingItem.LOCATION);
	}

	@Test
	void blankReplyWithNothingTickedMissesOnlyAbsentFields() {
		assertThat(ClaimExtractionRules.findMissingItems(extraction(withLocation(null)), Set.of(), true))
			.containsExactly(MissingItem.LOCATION);
	}

	@Test
	void reviewerTickedItemsCanComeInAnyCollectionAndDuplicatesCollapse() {
		var ticked = List.of(MissingItem.LOCATION, MissingItem.INCIDENT_DATE, MissingItem.LOCATION);

		assertThat(ClaimExtractionRules.findMissingItems(extraction(COMPLETE), ticked, false))
			.containsExactly(MissingItem.INCIDENT_DATE, MissingItem.LOCATION);
	}

	@Test
	void missingItemsComeBackInDeclarationOrderAndUnmodifiable() {
		var nothing = new IncidentDetails(null, null, null, null, null, null, Set.of());

		assertThat(ClaimExtractionRules.findMissingItems(extraction(nothing), Set.of(MissingItem.CATEGORY), false))
			.containsExactly(MissingItem.values())
			.isUnmodifiable();
	}

	private static ClaimExtraction extraction(IncidentDetails details) {
		return new ClaimExtraction("summary", "sentiment", details);
	}

	private static IncidentDetails withDescription(String description) {
		return new IncidentDetails(description, COMPLETE.incidentDate(), COMPLETE.incidentTime(), COMPLETE.location(), COMPLETE.category(), COMPLETE.policyNumber(), Set.of());
	}

	private static IncidentDetails withDate(String incidentDate) {
		return new IncidentDetails(COMPLETE.description(), incidentDate, COMPLETE.incidentTime(), COMPLETE.location(), COMPLETE.category(), COMPLETE.policyNumber(), Set.of());
	}

	private static IncidentDetails withLocation(String location) {
		return new IncidentDetails(COMPLETE.description(), COMPLETE.incidentDate(), COMPLETE.incidentTime(), location, COMPLETE.category(), COMPLETE.policyNumber(), Set.of());
	}

	private static IncidentDetails withCategory(ClaimCategory category) {
		return new IncidentDetails(COMPLETE.description(), COMPLETE.incidentDate(), COMPLETE.incidentTime(), COMPLETE.location(), category, COMPLETE.policyNumber(), Set.of());
	}

	private static IncidentDetails withAnswered(Set<MissingItem> answeredItems) {
		return new IncidentDetails(COMPLETE.description(), COMPLETE.incidentDate(), COMPLETE.incidentTime(), COMPLETE.location(), COMPLETE.category(), COMPLETE.policyNumber(), answeredItems);
	}
}
