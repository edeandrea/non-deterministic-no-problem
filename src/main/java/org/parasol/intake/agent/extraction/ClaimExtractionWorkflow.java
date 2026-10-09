package org.parasol.intake.agent.extraction;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import org.parasol.intake.MissingItem;
import org.parasol.intake.model.ClaimExtraction;
import org.parasol.intake.model.IncidentDetails;

import dev.langchain4j.agentic.agent.ErrorContext;
import dev.langchain4j.agentic.agent.ErrorRecoveryResult;
import dev.langchain4j.agentic.declarative.ErrorHandler;
import dev.langchain4j.agentic.declarative.Output;
import dev.langchain4j.agentic.declarative.ParallelAgent;
import dev.langchain4j.guardrail.OutputGuardrailException;
import dev.langchain4j.guardrail.OutputGuardrailResult;

/**
 * Parallel claim extraction workflow that fans out to the summary, sentiment and incident-details agents.
 * <p>
 * The parameters are the workflow's own inputs; the sub-agents read them from the agentic scope by name. There's no
 * {@code @ParallelExecutor}: Flow already runs the fork on Quarkus's {@code ManagedExecutor}, and since quarkus-flow
 * 1.2.0 (quarkiverse/quarkus-flow#1065) it carries the caller's OpenTelemetry context onto the agent threads, which is
 * what a custom executor would have been for. (Before 1.2.0 one also failed every run, quarkiverse/quarkus-flow#1057.)
 */
public interface ClaimExtractionWorkflow {
	/**
	 * Extracts a claim's summary, sentiment and incident details from its whole correspondence.
	 *
	 * @param correspondence the combined correspondence, oldest first, with the newest reply marked; the caller caps it
	 * at {@code parasol.intake.limits.max-body-characters} first, keeping the newest text
	 * @param extractedSoFar the fields extracted on earlier runs; all {@code null} for a new claim, never {@code null}
	 * itself (the agentic scope treats a {@code null} input as missing)
	 * @param requestedItems what we last asked the customer for, including reviewer-ticked items; empty if nothing
	 * @param sentDate the newest email's sent date as an ISO {@code yyyy-MM-dd} string
	 * @return the combined extraction
	 */
	@ParallelAgent(outputKey = "claimExtraction", subAgents = {
		ClaimSummaryAgent.class,
		ClaimSentimentAgent.class,
		IncidentDetailsAgent.class
	})
	ClaimExtraction extractClaim(String correspondence, IncidentDetails extractedSoFar, Collection<MissingItem> requestedItems, String sentDate);

	/**
	 * Combines the three agents' outputs.
	 *
	 * @param summary the summary agent's output
	 * @param sentiment the sentiment agent's output
	 * @param details the incident-details agent's output
	 * @return the combined extraction
	 */
	@Output
	static ClaimExtraction combineAgentOutputs(String summary, String sentiment, IncidentDetails details) {
		return new ClaimExtraction(summary, sentiment, details);
	}

	/**
	 * Treats an incident date or time the model never corrected as missing, instead of failing the run.
	 * <p>
	 * Only an {@link IncidentDetailsOutputGuardrail} date/time rejection carries a fallback (an
	 * {@link InvalidIncidentDetailsException} as the failure's cause); every other failure, including malformed JSON
	 * and an over-long summary, still fails the run.
	 *
	 * @param errorContext the failed agent and its exception
	 * @return the fallback details, or rethrow
	 */
	@ErrorHandler
	static ErrorRecoveryResult treatUncorrectedDatesAsMissing(ErrorContext errorContext) {
		return findFallbackDetails(errorContext.exception())
			.map(ErrorRecoveryResult::result)
			.orElseGet(ErrorRecoveryResult::throwException);
	}

	// The guardrail exception is wrapped (agent invocation, reflection), so walk the whole cause chain for it
	private static Optional<IncidentDetails> findFallbackDetails(Throwable failure) {
		return Stream.iterate(failure, Objects::nonNull, Throwable::getCause)
			.filter(OutputGuardrailException.class::isInstance)
			.map(OutputGuardrailException.class::cast)
			.map(OutputGuardrailException::result)
			.filter(Objects::nonNull)
			.flatMap(result -> result.<OutputGuardrailResult.Failure>failures().stream())
			.map(OutputGuardrailResult.Failure::cause)
			.filter(InvalidIncidentDetailsException.class::isInstance)
			.map(InvalidIncidentDetailsException.class::cast)
			.map(InvalidIncidentDetailsException::fallbackDetails)
			.findFirst();
	}
}
