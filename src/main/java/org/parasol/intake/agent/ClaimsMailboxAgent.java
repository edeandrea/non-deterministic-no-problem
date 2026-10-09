package org.parasol.intake.agent;

import java.util.Collection;

import org.parasol.intake.MissingItem;
import org.parasol.intake.agent.extraction.ClaimExtractionWorkflow;
import org.parasol.intake.agent.triage.ClaimStatusTools;
import org.parasol.intake.agent.triage.EmailClassifierAgent;
import org.parasol.intake.model.IncidentDetails;
import org.parasol.intake.model.IntakeOutcome;
import org.parasol.intake.model.MatchedClaim;

import dev.langchain4j.agentic.agent.ErrorContext;
import dev.langchain4j.agentic.agent.ErrorRecoveryResult;
import dev.langchain4j.agentic.declarative.ErrorHandler;
import dev.langchain4j.agentic.declarative.SequenceAgent;
import dev.langchain4j.invocation.InvocationParameters;

/**
 * The intake's root agent and its only entry point: classifies an email, then routes it on the matched claim first.
 * <p>
 * It has no side effects and never pauses: the intake workflow (task 08) runs it as one step and does all
 * persistence, mail and folder moves. There's no {@code @MemoryId}: every agent is stateless, and the history comes in
 * as arguments.
 */
public interface ClaimsMailboxAgent {
	/**
	 * Decides what to do with an inbound email.
	 *
	 * @param correspondence the email, or a matched claim's combined correspondence (newest reply marked), capped by
	 * the caller
	 * @param matchedClaim the claim matched in code, or {@link MatchedClaim#none()}; never {@code null} (the agentic
	 * scope treats {@code null} as missing)
	 * @param extractedSoFar the fields extracted on earlier runs; all {@code null} for a new claim, never {@code null}
	 * itself
	 * @param requestedItems what we last asked the customer for, including reviewer-ticked items; empty if nothing
	 * @param sentDate the newest email's sent date as an ISO {@code yyyy-MM-dd} string
	 * @param invocationParameters always {@link ClaimStatusTools#scopedTo ClaimStatusTools.scopedTo(matchedClaim)}: scopes
	 * the follow-up agent's status tool to the matched claim, so the model can't pick another claim. It goes into the
	 * scope's execution context, never into a prompt
	 * @return the outcome
	 */
	// invocationParameters is a root argument only because quarkus-langchain4j 1.14.1's build-time check
	// (AgenticProcessor#validateAgenticParameterTypes) skips AgenticScope and @MemoryId parameters but not
	// InvocationParameters: ClaimFollowUpAgent's one fails the build ("No agent provides an output key named
	// 'invocationParameters'") unless a caller-provided parameter has the same name. langchain4j-agentic itself fills it
	// from the execution context at runtime (AgentUtil). quarkiverse/quarkus-langchain4j#2950: once fixed, a @BeforeCall
	// can write the parameters into the scope instead, and this argument goes
	@SequenceAgent(outputKey = "intakeOutcome", subAgents = {
		EmailClassifierAgent.class,
		EmailRouter.class
	})
	IntakeOutcome process(
		String correspondence,
		MatchedClaim matchedClaim,
		IncidentDetails extractedSoFar,
		Collection<MissingItem> requestedItems,
		String sentDate,
		InvocationParameters invocationParameters);

	/**
	 * Treats an incident date or time the model never corrected as missing, as {@link ClaimExtractionWorkflow} does on
	 * its own.
	 * <p>
	 * Needed here because a nested workflow runs in its parent's agentic scope, whose error handler is the root's:
	 * {@code ClaimExtractionWorkflow}'s own {@code @ErrorHandler} only applies when it's called directly.
	 *
	 * @param errorContext the failed agent and its exception
	 * @return the fallback details, or rethrow
	 */
	@ErrorHandler
	static ErrorRecoveryResult treatUncorrectedDatesAsMissing(ErrorContext errorContext) {
		return ClaimExtractionWorkflow.treatUncorrectedDatesAsMissing(errorContext);
	}
}
