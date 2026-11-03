package org.parasol.intake.workflow.review;

/**
 * What an intake run does once its decision steps have run: the flow's {@code afterDecision} switch reads it.
 */
public enum AfterDecision {
	/**
	 * The run ends: the decision was applied, or a fault came after the claim's status had already changed.
	 */
	END,

	/**
	 * The run waits for the reviewer's next decision: a fault put the claim back in review.
	 */
	WAIT_AGAIN
}
