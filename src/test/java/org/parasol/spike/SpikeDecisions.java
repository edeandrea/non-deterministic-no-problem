package org.parasol.spike;

import org.parasol.intake.agent.extraction.IncidentAssessment;
import org.parasol.intake.agent.triage.EmailClassifierDecisionAgent;
import org.parasol.intake.model.EmailType;

import dev.langchain4j.service.decision.Choice;
import dev.langchain4j.service.decision.Decide;
import dev.langchain4j.service.decision.DecisionResult;

/**
 * #221 spike: the same questions as the two decision agents, as a plain decision service, so the harness gets the
 * probabilities and token usage and can point it at any decision model. Same parameter name, so the same input field.
 */
interface SpikeDecisions {
	@Decide(EmailClassifierDecisionAgent.QUESTION)
	DecisionResult<Choice<EmailType>> classifyEmail(String correspondence);

	DecisionResult<IncidentAssessment> assessIncident(String correspondence);
}
