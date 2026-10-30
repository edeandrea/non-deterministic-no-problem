package org.parasol.intake.workflow.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.parasol.intake.model.IntakeOutcome;
import org.parasol.intake.observability.EmailOutcome;
import org.parasol.intake.workflow.IntakeState;
import org.parasol.intake.workflow.IntakeState.Disposition;

class FileEmailStepTests {
	@Test
	void anEmailThatBypassedTheAgentsIsCountedByWhyItWasHandledOutsideThem() {
		assertThat(FileEmailStep.outcomeOf(state(Disposition.WRONG_SENDER, null))).isEqualTo(EmailOutcome.NO_MATCHING_CLAIM);
		assertThat(FileEmailStep.outcomeOf(state(Disposition.WHICH_CLAIM, null))).isEqualTo(EmailOutcome.NEEDS_CLARIFICATION);
	}

	@Test
	void aRejectedPolicyIsCountedAsSuchWhateverTheAgentsDecided() {
		assertThat(FileEmailStep.outcomeOf(state(Disposition.POLICY_REJECTED, new IntakeOutcome.NotAClaim()))).isEqualTo(EmailOutcome.POLICY_REJECTED);
	}

	@Test
	void anEmailOnTheMainPathIsCountedByWhatTheAgentsDecided() {
		assertThat(FileEmailStep.outcomeOf(state(Disposition.CONTINUE, new IntakeOutcome.NotAClaim()))).isEqualTo(EmailOutcome.NOT_A_CLAIM);
		assertThat(FileEmailStep.outcomeOf(state(Disposition.CONTINUE, new IntakeOutcome.NoMatchingClaim()))).isEqualTo(EmailOutcome.NO_MATCHING_CLAIM);
		assertThat(FileEmailStep.outcomeOf(state(Disposition.CONTINUE, new IntakeOutcome.StatusReply("In Process")))).isEqualTo(EmailOutcome.STATUS_QUESTION);
	}

	private static IntakeState state(Disposition disposition, IntakeOutcome outcome) {
		return new IntakeState("<id@example.com>", "jane.doe@example.com", null, disposition, false, List.of(), outcome, null, false, Set.of(), false, false, null);
	}
}
