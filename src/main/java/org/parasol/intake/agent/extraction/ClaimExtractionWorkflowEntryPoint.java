package org.parasol.intake.agent.extraction;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.arc.Unremovable;

/**
 * Injects {@link ClaimExtractionWorkflow} from {@code src/main} so the build-time agentic validation treats its
 * parameters as caller-provided inputs.
 * <p>
 * quarkus-langchain4j only collects those keys from CDI injection points of the entry agent. Nothing injects this
 * bean, so it's {@link Unremovable}: otherwise ArC drops it as unused, its injection point goes with it, and the
 * validation fails again. The constructor parameter is that injection point, so it's never stored. Remove this in
 * task 06, once the workflow is nested under the root agent, so it doesn't become a second root.
 */
@ApplicationScoped
@Unremovable
class ClaimExtractionWorkflowEntryPoint {
	@SuppressWarnings("unused")
	ClaimExtractionWorkflowEntryPoint(ClaimExtractionWorkflow workflow) {
	}
}
