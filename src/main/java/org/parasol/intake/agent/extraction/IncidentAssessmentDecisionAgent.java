package org.parasol.intake.agent.extraction;

import dev.langchain4j.agentic.Agent;
import io.quarkiverse.langchain4j.ModelName;

/**
 * #221 spike: {@link IncidentAssessment} from a decision model, the typed part of {@link IncidentDetailsAgent}.
 */
public interface IncidentAssessmentDecisionAgent {
	@Agent(value = "Assesses which incident details the correspondence states", outputKey = "assessment")
	@ModelName("claim-decisions")
	IncidentAssessment assessIncident(String correspondence);
}
