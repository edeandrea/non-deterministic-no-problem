package org.parasol.intake.agent;

import jakarta.enterprise.inject.spi.CDI;

import dev.langchain4j.agentic.observability.AgentInvocationError;
import dev.langchain4j.agentic.observability.AgentListener;

/**
 * Ends the Flow runs of an agentic system whose root invocation failed, through {@link AgenticWorkflowRuns}.
 * <p>
 * Registered on the root agent only ({@link ClaimsMailboxAgent#endFlowRunsOnFailure()}), and not inherited by its
 * sub-agents: a sub-agent's error is reported before its error handler decides whether to recover, but the root's is
 * reported only once the whole invocation has failed. It isn't a CDI bean because quarkus-langchain4j attaches every
 * {@code AgentListener} bean to every agent.
 *
 * @deprecated A workaround for a quarkus-flow-langchain4j 1.2.0 bug, see {@link AgenticWorkflowRuns}. Remove it, and the
 * supplier on {@link ClaimsMailboxAgent}, once a quarkus-flow release includes
 * <a href="https://github.com/quarkiverse/quarkus-flow/pull/1095">quarkiverse/quarkus-flow#1095</a>.
 */
@Deprecated
final class FailedAgenticSystemListener implements AgentListener {
	static final FailedAgenticSystemListener INSTANCE = new FailedAgenticSystemListener();

	private FailedAgenticSystemListener() {
	}

	@Override
	public void onAgentInvocationError(AgentInvocationError error) {
		if (error.agent().parent() == null) {
			CDI.current()
				.select(AgenticWorkflowRuns.class)
				.get()
				.failRunsOf(error.agenticScope(), error.error());
		}
	}
}
