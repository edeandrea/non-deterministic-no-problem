package org.parasol.intake.workflow.review;

/**
 * What became of the attempt to hand a faulted run's review to a new run.
 */
public enum ReviewHandover {
	/**
	 * There was nothing to hand over: the faulted run owned no review. Its claim is nobody's now, so the failure is a
	 * fatal one.
	 */
	NOT_NEEDED,

	/**
	 * A new run took the review over and is waiting for the decision.
	 */
	DONE,

	/**
	 * The run still owned the review when it faulted, but by the time the hand-over went to claim it another run held it.
	 */
	ALREADY_ELSEWHERE;

	/**
	 * Whether the claim is still being handled by some run, in which case its customer must not be told that their email
	 * failed: it was filed and answered when the claim was created, and the only thing that faulted is a run waiting for a
	 * reviewer.
	 *
	 * @return {@code true} for anything but {@link #NOT_NEEDED}
	 */
	public boolean isClaimStillHandled() {
		return this != NOT_NEEDED;
	}
}
