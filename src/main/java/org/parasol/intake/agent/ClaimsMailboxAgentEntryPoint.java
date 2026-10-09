package org.parasol.intake.agent;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.arc.Unremovable;

/**
 * Injects {@link ClaimsMailboxAgent} from {@code src/main} so the build-time agentic validation treats its parameters
 * as caller-provided inputs.
 * <p>
 * quarkus-langchain4j only collects those keys from CDI injection points of the entry agent. Nothing injects this
 * bean, so it's {@link Unremovable}: otherwise ArC drops it as unused, its injection point goes with it, and the
 * validation fails again. The constructor parameter is that injection point, so it's never stored. Remove this in
 * task 08, once the intake workflow injects the agent.
 */
@ApplicationScoped
@Unremovable
class ClaimsMailboxAgentEntryPoint {
	@SuppressWarnings("unused")
	ClaimsMailboxAgentEntryPoint(ClaimsMailboxAgent agent) {
	}
}
