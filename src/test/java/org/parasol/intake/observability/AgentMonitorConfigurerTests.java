package org.parasol.intake.observability;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.parasol.intake.IntakeFlowTestBase;
import org.parasol.intake.agent.ClaimsMailboxAgent;
import org.parasol.testing.StubbedModelsTestProfile;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkiverse.langfuse.runtime.otel.LangfuseSpanProcessor;

/**
 * Two things that would otherwise grow or cost without anyone noticing: the agent monitor keeps nothing outside dev mode,
 * and tests don't export their spans to Langfuse.
 */
@QuarkusTest
@TestProfile(StubbedModelsTestProfile.class)
class AgentMonitorConfigurerTests extends IntakeFlowTestBase {
	@Inject
	ClaimsMailboxAgent agent;

	@Inject
	Instance<LangfuseSpanProcessor> langfuseSpanProcessor;

	@Test
	void theAgentMonitorRetainsNothingOutsideDevMode() {
		var claim = createClaim(SENDER, COMPLETE_DETAILS).claim();
		this.scenario.awaitWaitingRun(claim.id);

		var monitor = this.agent.agentMonitor();

		assertThat(monitor.ongoingExecutions()).isEmpty();
		assertThat(monitor.successfulExecutions()).isEmpty();
		assertThat(monitor.failedExecutions()).isEmpty();
	}

	@Test
	void testsDoNotExportSpansToLangfuse() {
		assertThat(this.langfuseSpanProcessor.isUnsatisfied()).isTrue();
	}
}
