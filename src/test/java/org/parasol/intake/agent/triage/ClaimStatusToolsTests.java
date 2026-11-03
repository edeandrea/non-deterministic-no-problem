package org.parasol.intake.agent.triage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.parasol.intake.model.MatchedClaim;

import dev.langchain4j.invocation.InvocationParameters;

class ClaimStatusToolsTests {
	private final ClaimStatusTools tools = new ClaimStatusTools();

	@Test
	void returnsTheMatchedClaim() {
		assertThat(this.tools.findClaimStatus(ClaimStatusTools.scopedTo(MatchedClaim.of("CLM01001009", "In Process"))))
			.isEqualTo("Claim CLM01001009 has the status: In Process");
	}

	@Test
	void hasNothingToLookUpWithoutAMatchedClaim() {
		assertThat(this.tools.findClaimStatus(ClaimStatusTools.scopedTo(MatchedClaim.none())))
			.isEqualTo("There is no claim to look up.");
		assertThat(this.tools.findClaimStatus(new InvocationParameters()))
			.isEqualTo("There is no claim to look up.");
	}
}
