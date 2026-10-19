package org.parasol.intake.failure;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.parasol.intake.workflow.ClaimIntakeFlow;
import org.parasol.intake.workflow.IntakeRun;
import org.parasol.intake.workflow.review.ReviewRecoverer;

import io.quarkus.logging.Log;
import io.serverlessworkflow.impl.lifecycle.WorkflowExecutionListener;
import io.serverlessworkflow.impl.lifecycle.WorkflowFailedEvent;

/**
 * Handles every faulted intake run, before or after its review wait: a review the run owned is handed to a new run
 * ({@link ReviewRecoverer}), then {@link IntakeFailureHandler#handle}. The work (IMAP and SMTP)
 * runs off Flow's thread. No partial claim is left behind: each persistence step committed only its own transaction.
 */
@ApplicationScoped
public class IntakeFailureListener implements WorkflowExecutionListener {
	private final IntakeFailureHandler failureHandler;
	private final ReviewRecoverer reviewRecoverer;
	private final ManagedExecutor executor;

	IntakeFailureListener(IntakeFailureHandler failureHandler, ReviewRecoverer reviewRecoverer, ManagedExecutor executor) {
		this.failureHandler = failureHandler;
		this.reviewRecoverer = reviewRecoverer;
		this.executor = executor;
	}

	@Override
	public void onWorkflowFailed(WorkflowFailedEvent event) {
		var context = event.workflowContext();

		if (ClaimIntakeFlow.NAME.equals(context.definition().id().name())) {
			context.instanceData()
				.input()
				.as(IntakeRun.class)
				.ifPresentOrElse(
					run -> this.executor.execute(() -> handle(context.instanceData().id(), run, event.cause())),
					() -> Log.errorf(event.cause(), "A %s run failed and its input couldn't be read", ClaimIntakeFlow.NAME));
		}
	}

	private void handle(String runId, IntakeRun run, Throwable cause) {
		try {
			this.reviewRecoverer.recover(runId, run);
		}
		catch (RuntimeException e) {
			Log.errorf(e, "Couldn't hand over the review of run %s", runId);
		}

		this.failureHandler.handle(run.messageId(), cause);
	}
}
