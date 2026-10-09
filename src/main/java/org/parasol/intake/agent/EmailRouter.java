package org.parasol.intake.agent;

import org.parasol.intake.agent.extraction.ClaimExtractionWorkflow;
import org.parasol.intake.agent.triage.ClaimFollowUpAgent;
import org.parasol.intake.agent.triage.UnmatchedEmailAgent;
import org.parasol.intake.model.ClaimExtraction;
import org.parasol.intake.model.EmailType;
import org.parasol.intake.model.IntakeOutcome;
import org.parasol.intake.model.IntakeOutcome.NewClaim;
import org.parasol.intake.model.IntakeOutcome.PendingClaimUpdate;
import org.parasol.intake.model.IntakeOutcome.StatusReply;
import org.parasol.intake.model.MatchedClaim;

import dev.langchain4j.agentic.declarative.ActivationCondition;
import dev.langchain4j.agentic.declarative.ConditionalAgent;
import dev.langchain4j.agentic.declarative.Output;
import dev.langchain4j.agentic.scope.AgenticScope;

/**
 * Routes an email on the matched claim first, then on the classifier's label (no LLM).
 *
 * <table>
 * <caption>Routes</caption>
 * <tr><th>Matched claim</th><th>Email type</th><th>Route</th><th>Outcome</th></tr>
 * <tr><td>pending</td><td>any</td><td>{@link ClaimExtractionWorkflow}</td><td>{@link PendingClaimUpdate}</td></tr>
 * <tr><td>any other status</td><td>any</td><td>{@link ClaimFollowUpAgent}</td><td>{@link StatusReply}</td></tr>
 * <tr><td>none</td><td>{@code NEW_CLAIM}</td><td>{@link ClaimExtractionWorkflow}</td><td>{@link NewClaim}</td></tr>
 * <tr><td>none</td><td>{@code NOT_A_CLAIM}, {@code CLAIM_FOLLOW_UP}</td><td>{@link UnmatchedEmailAgent}</td><td>{@link IntakeOutcome.NotAClaim}, {@link IntakeOutcome.NoMatchingClaim}</td></tr>
 * </table>
 */
public interface EmailRouter {
	/**
	 * Routes the email to one branch and returns that branch's outcome.
	 *
	 * @param matchedClaim the claim matched in code, or {@link MatchedClaim#none()}
	 * @param emailType the classifier's label
	 * @return the outcome
	 */
	@ConditionalAgent(outputKey = "intakeOutcome", subAgents = {
		ClaimExtractionWorkflow.class,
		ClaimFollowUpAgent.class,
		UnmatchedEmailAgent.class
	})
	IntakeOutcome routeEmail(MatchedClaim matchedClaim, EmailType emailType);

	/**
	 * Extract from a matched pending claim's thread, or from an unmatched new claim.
	 *
	 * @param matchedClaim the claim matched in code, or {@link MatchedClaim#none()}
	 * @param emailType the classifier's label
	 * @return whether this branch runs
	 */
	@ActivationCondition(value = ClaimExtractionWorkflow.class, description = "a matched pending claim, or an unmatched new claim")
	static boolean shouldExtractClaim(MatchedClaim matchedClaim, EmailType emailType) {
		return EmailRoute.route(matchedClaim, emailType) == EmailRoute.EXTRACT;
	}

	/**
	 * Answer an email about a matched claim in any other status.
	 *
	 * @param matchedClaim the claim matched in code, or {@link MatchedClaim#none()}
	 * @param emailType the classifier's label
	 * @return whether this branch runs
	 */
	@ActivationCondition(value = ClaimFollowUpAgent.class, description = "a matched claim that isn't pending")
	static boolean shouldAnswerFollowUp(MatchedClaim matchedClaim, EmailType emailType) {
		return EmailRoute.route(matchedClaim, emailType) == EmailRoute.FOLLOW_UP;
	}

	/**
	 * Map an unmatched email that isn't a new claim to its outcome.
	 *
	 * @param matchedClaim the claim matched in code, or {@link MatchedClaim#none()}
	 * @param emailType the classifier's label
	 * @return whether this branch runs
	 */
	@ActivationCondition(value = UnmatchedEmailAgent.class, description = "an unmatched email that isn't a new claim")
	static boolean shouldHandleUnmatched(MatchedClaim matchedClaim, EmailType emailType) {
		return EmailRoute.route(matchedClaim, emailType) == EmailRoute.UNMATCHED;
	}

	/**
	 * Turns the output of the branch that ran into the outcome.
	 * <p>
	 * It reads the scope rather than taking one parameter per branch output: only one branch runs, and the agentic
	 * scope throws {@code MissingArgumentException} for every parameter whose key is missing.
	 *
	 * @param agenticScope the router's scope
	 * @return the outcome
	 */
	@Output
	static IntakeOutcome combineBranchOutput(AgenticScope agenticScope) {
		var matchedClaim = (MatchedClaim) agenticScope.readState("matchedClaim");
		var emailType = (EmailType) agenticScope.readState("emailType");

		return switch (EmailRoute.route(matchedClaim, emailType)) {
			case EXTRACT -> toExtractionOutcome(matchedClaim, (ClaimExtraction) agenticScope.readState("claimExtraction"));
			case FOLLOW_UP -> new StatusReply((String) agenticScope.readState("statusAnswer"));
			case UNMATCHED -> (IntakeOutcome) agenticScope.readState("unmatchedOutcome");
		};
	}

	/**
	 * The extraction's outcome: an update for a matched (pending) claim, a new claim otherwise.
	 *
	 * @param matchedClaim the claim matched in code, or {@link MatchedClaim#none()}
	 * @param extraction the extraction workflow's output
	 * @return {@link PendingClaimUpdate} or {@link NewClaim}
	 */
	static IntakeOutcome toExtractionOutcome(MatchedClaim matchedClaim, ClaimExtraction extraction) {
		return matchedClaim.isMatched() ?
		       new PendingClaimUpdate(extraction) :
		       new NewClaim(extraction);
	}
}
