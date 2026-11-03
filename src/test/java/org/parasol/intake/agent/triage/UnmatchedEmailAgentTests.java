package org.parasol.intake.agent.triage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.parasol.intake.model.EmailType;
import org.parasol.intake.model.IntakeOutcome;

class UnmatchedEmailAgentTests {
	@Test
	void anUnmatchedFollowUpHasNoMatchingClaim() {
		assertThat(UnmatchedEmailAgent.decideUnmatchedOutcome(EmailType.CLAIM_FOLLOW_UP))
			.isEqualTo(new IntakeOutcome.NoMatchingClaim());
	}

	@Test
	void anUnmatchedEmailThatIsNotAClaimIsNotAClaim() {
		assertThat(UnmatchedEmailAgent.decideUnmatchedOutcome(EmailType.NOT_A_CLAIM))
			.isEqualTo(new IntakeOutcome.NotAClaim());
	}
}
