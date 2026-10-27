package org.parasol.intake.review;

import io.serverlessworkflow.impl.WorkflowStatus;

/**
 * Thrown when a claim's review run exists but isn't {@link WorkflowStatus#WAITING}, so there's nothing for the decision
 * to wake.
 */
public class ReviewRunNotWaitingException extends RuntimeException {
	private final long claimId;
	private final String runId;
	private final WorkflowStatus status;

	/**
	 * Creates the exception for the claim and run that isn't waiting.
	 *
	 * @param claimId the claim id
	 * @param runId the id of the run that isn't waiting
	 * @param status the run's actual status
	 */
	public ReviewRunNotWaitingException(long claimId, String runId, WorkflowStatus status) {
		// An expected outcome that is mapped to a response: no stack trace is ever shown or logged, so don't compute one
		super("Claim %d's review run %s is %s, not waiting".formatted(claimId, runId, status), null, false, false);
		this.claimId = claimId;
		this.runId = runId;
		this.status = status;
	}

	/**
	 * The id of the claim whose review run isn't waiting.
	 *
	 * @return the claim id
	 */
	public long claimId() {
		return this.claimId;
	}

	/**
	 * The id of the run that isn't waiting.
	 *
	 * @return the run id
	 */
	public String runId() {
		return this.runId;
	}

	/**
	 * The run's actual status.
	 *
	 * @return the status
	 */
	public WorkflowStatus status() {
		return this.status;
	}
}
