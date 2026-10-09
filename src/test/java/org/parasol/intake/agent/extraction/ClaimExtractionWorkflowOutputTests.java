package org.parasol.intake.agent.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.parasol.claim.model.ClaimCategory;
import org.parasol.intake.MissingItem;
import org.parasol.intake.model.ClaimExtraction;
import org.parasol.intake.model.IncidentDetails;

class ClaimExtractionWorkflowOutputTests {
	@Test
	void outputCombinesTheThreeAgentResults() {
		var details = new IncidentDetails("car hit a tree", "2026-10-07", null, "Elm Street", ClaimCategory.SINGLE_VEHICLE, null, Set.of(MissingItem.LOCATION));

		assertThat(ClaimExtractionWorkflow.combineAgentOutputs("summary", "sentiment", details))
			.isEqualTo(new ClaimExtraction("summary", "sentiment", details));
	}
}
