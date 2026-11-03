package org.parasol.intake.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.parasol.intake.model.ClaimExtraction;
import org.parasol.intake.model.EmailType;
import org.parasol.intake.model.IncidentDetails;
import org.parasol.intake.model.IntakeOutcome;
import org.parasol.intake.model.MatchedClaim;
import org.junit.jupiter.api.Test;

class EmailRouterTests {
	private static final ClaimExtraction EXTRACTION = new ClaimExtraction("summary", "sentiment", new IncidentDetails(null, null, null, null, null, null, Set.of()));

	// Every status a matched claim can have: the intake's two pending ones, the rest from the seed data (In Process included)
	private static final Map<String, EmailRoute> MATCHED_ROUTES = Map.of(
		"Pending Information", EmailRoute.EXTRACT,
		"Pending Review", EmailRoute.EXTRACT,
		"pending review", EmailRoute.EXTRACT,
		"New", EmailRoute.FOLLOW_UP,
		"In Process", EmailRoute.FOLLOW_UP,
		"Processed", EmailRoute.FOLLOW_UP,
		"Denied", EmailRoute.FOLLOW_UP);

	static Stream<Arguments> everyClaimAndEmailType() {
		var matched = MATCHED_ROUTES.entrySet().stream()
			.flatMap(entry -> Stream.of(EmailType.values())
				.map(type -> Arguments.of(MatchedClaim.of("CLM01000000", entry.getKey()), type, entry.getValue())));
		var unmatched = Stream.of(
			Arguments.of(MatchedClaim.none(), EmailType.NEW_CLAIM, EmailRoute.EXTRACT),
			Arguments.of(MatchedClaim.none(), EmailType.CLAIM_FOLLOW_UP, EmailRoute.UNMATCHED),
			Arguments.of(MatchedClaim.none(), EmailType.NOT_A_CLAIM, EmailRoute.UNMATCHED));
		return Stream.concat(matched, unmatched);
	}

	@ParameterizedTest
	@MethodSource("everyClaimAndEmailType")
	void exactlyOneConditionIsTrueAndTheMatchedClaimWinsOverTheLabel(MatchedClaim claim, EmailType type, EmailRoute expected) {
		var conditions = List.of(
			EmailRouter.shouldExtractClaim(claim, type),
			EmailRouter.shouldAnswerFollowUp(claim, type),
			EmailRouter.shouldHandleUnmatched(claim, type));

		assertThat(conditions)
			.containsExactly(expected == EmailRoute.EXTRACT, expected == EmailRoute.FOLLOW_UP, expected == EmailRoute.UNMATCHED);
	}

	@Test
	void anExtractionIsAnUpdateForAMatchedClaimAndANewClaimOtherwise() {
		assertThat(EmailRouter.toExtractionOutcome(MatchedClaim.of("CLM01000000", "Pending Information"), EXTRACTION))
			.isEqualTo(new IntakeOutcome.PendingClaimUpdate(EXTRACTION));
		assertThat(EmailRouter.toExtractionOutcome(MatchedClaim.none(), EXTRACTION))
			.isEqualTo(new IntakeOutcome.NewClaim(EXTRACTION));
	}
}
