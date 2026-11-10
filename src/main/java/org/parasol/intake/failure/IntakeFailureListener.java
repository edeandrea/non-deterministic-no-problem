package org.parasol.intake.failure;

import java.util.concurrent.ExecutorService;

import jakarta.enterprise.context.ApplicationScoped;

import org.parasol.intake.observability.FailureOutcome;
import org.parasol.intake.workflow.ClaimIntakeFlow;
import org.parasol.intake.workflow.IntakeRun;
import org.parasol.intake.workflow.review.ReviewRecoverer;
import org.parasol.intake.workflow.review.ReviewHandover;

import io.quarkus.logging.Log;
import io.quarkus.virtual.threads.VirtualThreads;
import io.serverlessworkflow.impl.lifecycle.WorkflowExecutionListener;
import io.serverlessworkflow.impl.lifecycle.WorkflowFailedEvent;

/**
 * Handles every faulted intake run, before or after its review wait: a review the run owned is handed to a new run
 * ({@link ReviewRecoverer}), and then {@link IntakeFailureHandler#handle} files and answers the email if it still needs
 * answering. Whether the hand-over happened only decides how the failure is counted, not what the customer hears. The
 * work (IMAP and SMTP) runs off Flow's thread. No partial claim is left behind: each persistence step committed only its
 * own transaction.
 */
@ApplicationScoped
public class IntakeFailureListener implements WorkflowExecutionListener {
	private final IntakeFailureHandler failureHandler;
	private final ReviewRecoverer reviewRecoverer;
	private final ExecutorService executor;

	IntakeFailureListener(IntakeFailureHandler failureHandler, ReviewRecoverer reviewRecoverer, @VirtualThreads ExecutorService executor) {
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

	// What the customer hears is the handler's call, from where the email is. What the recovery decides is only how the
	// failure is counted: a claim another run picked up is self-healing, one nothing picked up needs an operator.
	private void handle(String runId, IntakeRun run, Throwable cause) {
		var outcome = recover(runId, run).isClaimStillHandled() ? FailureOutcome.RECOVERED : FailureOutcome.FATAL;

		this.failureHandler.handle(run.messageId(), cause, outcome);
	}

	private ReviewHandover recover(String runId, IntakeRun run) {
		try {
			return this.reviewRecoverer.recover(runId, run);
		}
		catch (RuntimeException e) {
			Log.errorf(e, "Couldn't hand over the review of run %s", runId);

			return ReviewHandover.NOT_NEEDED;
		}
	}
}
