package org.parasol.intake;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.enterprise.context.ApplicationScoped;

import org.parasol.intake.workflow.ClaimIntakeFlow;
import org.parasol.intake.workflow.IntakeRun;

import io.serverlessworkflow.impl.lifecycle.WorkflowExecutionListener;
import io.serverlessworkflow.impl.lifecycle.WorkflowStartedEvent;

/**
 * Records every {@link ClaimIntakeFlow} run that starts, with its input, so a test knows each run's id and the conversation
 * id the starter gave it without reading either from a span. Quarkus-flow binds every {@code WorkflowExecutionListener} bean.
 * <p>
 *   Shared by every test of the boot, so a test resets it first.
 * </p>
 */
@ApplicationScoped
public class StartedRuns implements WorkflowExecutionListener {
	/**
	 * A run that started.
	 *
	 * @param runId The run's instance id
	 * @param input The run's input
	 */
	public record StartedRun(String runId, IntakeRun input) {
		public String conversationId() {
			return this.input.conversationId();
		}
	}

	private final List<StartedRun> runs = new CopyOnWriteArrayList<>();

	@Override
	public void onWorkflowStarted(WorkflowStartedEvent event) {
		if (ClaimIntakeFlow.NAME.equals(event.workflowContext().definition().id().name())) {
			var instance = event.workflowContext().instanceData();
			instance.input().as(IntakeRun.class)
				.ifPresent(input -> this.runs.add(new StartedRun(instance.id(), input)));
		}
	}

	/**
	 * The runs started since the last {@link #reset()}, oldest first.
	 */
	public List<StartedRun> all() {
		return List.copyOf(this.runs);
	}

	/**
	 * The runs started for a sender's emails, oldest first.
	 *
	 * @param sender The sender's address, as it was written
	 */
	public List<StartedRun> of(String sender) {
		return this.runs.stream()
			.filter(run -> sender.equalsIgnoreCase(run.input().sender()))
			.toList();
	}

	public void reset() {
		this.runs.clear();
	}
}
