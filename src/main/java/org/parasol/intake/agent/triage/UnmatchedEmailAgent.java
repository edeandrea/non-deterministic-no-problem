package org.parasol.intake.agent.triage;

import org.parasol.intake.model.EmailType;
import org.parasol.intake.model.IntakeOutcome;
import org.parasol.intake.model.IntakeOutcome.NoMatchingClaim;
import org.parasol.intake.model.IntakeOutcome.NotAClaim;

import dev.langchain4j.agentic.Agent;

/**
 * The non-LLM agent for an email that matched no claim and isn't a new claim.
 */
public final class UnmatchedEmailAgent {
	private UnmatchedEmailAgent() {
	}

	/**
	 * Turns the email's type into its outcome: a follow-up gets the "no matching claim" reply, anything else the
	 * not-a-claim reply.
	 *
	 * @param emailType the classifier's label
	 * @return {@link NoMatchingClaim} or {@link NotAClaim}
	 */
	@Agent(value = "Decides the outcome of an unmatched email that isn't a new claim", outputKey = "unmatchedOutcome")
	public static IntakeOutcome decideUnmatchedOutcome(EmailType emailType) {
		return (emailType == EmailType.CLAIM_FOLLOW_UP) ?
		       new NoMatchingClaim() :
		       new NotAClaim();
	}
}
