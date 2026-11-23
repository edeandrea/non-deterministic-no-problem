package org.parasol.intake;

import static io.quarkiverse.flow.dsl.FlowDSL.function;
import static io.quarkiverse.flow.dsl.FlowWorkflowBuilder.workflow;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;

import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.persistence.PersistenceInstanceReader;

// The app boots with the agentic extension and the four quarkus-flow modules, and Flow's persistence is live (read through its API only)
@QuarkusTest
class IntakeExtensionsTests {
	@Inject
	WorkflowApplication application;

	@Test
	void flowPersistenceIsAvailable() {
		assertThat(Arc.container().select(PersistenceInstanceReader.class).isResolvable())
			.isTrue();
	}

	// ReviewRecoverer.handOver and ClaimIntakeDispatcher.startRun create an instance they may never start: since
	// serverlessworkflow 7.35.4 (open-workflow-specification/sdk-java#1816) only start() registers it, so it can't leak
	@Test
	void anInstanceThatIsNeverStartedIsNotActive() {
		var definition = this.application.workflowDefinition(workflow("intake-extensions-unstarted")
			.tasks(function("echo", input -> input, String.class, String.class))
			.build());

		var instance = definition.instance("never started");

		assertThat(definition.activeInstance(instance.id()))
			.isEmpty();
	}
}