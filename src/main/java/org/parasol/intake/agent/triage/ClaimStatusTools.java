package org.parasol.intake.agent.triage;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import org.parasol.intake.model.MatchedClaim;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.invocation.InvocationParameters;

/**
 * {@link ClaimFollowUpAgent}'s read-only status tool, scoped to the claim the intake already matched.
 * <p>
 * The tool takes no argument from the model: the matched claim arrives as {@link InvocationParameters}, which
 * {@code ClaimsMailboxAgent} puts into the agentic scope before the agents run ({@link #scopedTo}). So a prompt
 * injection can't make it look up another claim. It reads nothing from the database either: the status is the one the
 * intake matched the claim with, so the tool can't change anything.
 */
@ApplicationScoped
public class ClaimStatusTools {
	private static final String MATCHED_CLAIM = "matchedClaim";

	/**
	 * The invocation parameters that scope this tool to one claim.
	 *
	 * @param matchedClaim the claim the intake matched
	 * @return the parameters to put into the agentic scope's execution context
	 */
	public static InvocationParameters scopedTo(MatchedClaim matchedClaim) {
		return InvocationParameters.from(MATCHED_CLAIM, matchedClaim);
	}

	/**
	 * Looks up the status of the claim the customer is writing about.
	 *
	 * @param parameters the invocation parameters from {@link #scopedTo}; never from the model
	 * @return the claim's number and status, or a note that there's no claim to look up
	 */
	@Tool("Looks up the claim number and current status of the claim the customer is writing about")
	public String findClaimStatus(InvocationParameters parameters) {
		return Optional.ofNullable(parameters.<MatchedClaim>get(MATCHED_CLAIM))
			.filter(MatchedClaim::isMatched)
			.map(claim -> "Claim %s has the status: %s".formatted(claim.claimNumber(), claim.status()))
			.orElse("There is no claim to look up.");
	}
}
