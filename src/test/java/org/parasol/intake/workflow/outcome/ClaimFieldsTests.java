package org.parasol.intake.workflow.outcome;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.parasol.claim.model.Claim;
import org.parasol.intake.MissingItem;

class ClaimFieldsTests {
	@Test
	void theDescriptionIsNotDescribedWhenItWasRequestedEvenWithASummary() {
		var claim = claimWithSummary("The customer reports an incident but gives no details.");

		assertThat(ClaimFields.extractedSoFar(claim, Set.of(MissingItem.INCIDENT_DESCRIPTION, MissingItem.LOCATION)).incidentDescribed())
			.isFalse();
	}

	@Test
	void aSummaryWithoutARequestMeansTheDescriptionWasJudgedPresent() {
		assertThat(ClaimFields.extractedSoFar(claimWithSummary("Car hit a post."), Set.of(MissingItem.LOCATION)).incidentDescribed())
			.isTrue();
	}

	@Test
	void noSummaryAndNoRequestMeansUnjudged() {
		assertThat(ClaimFields.extractedSoFar(claimWithSummary("  "), Set.of()).incidentDescribed())
			.isNull();
		assertThat(ClaimFields.extractedSoFar(claimWithSummary(null), Set.of()).incidentDescribed())
			.isNull();
	}

	private static Claim claimWithSummary(String summary) {
		var claim = new Claim();
		claim.summary = summary;

		return claim;
	}
}
