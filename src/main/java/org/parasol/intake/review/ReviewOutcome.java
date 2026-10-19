package org.parasol.intake.review;

/**
 * What a reviewer decided about a claim in {@code Pending Review}.
 */
public enum ReviewOutcome {
	/**
	 * The claim is ready: it goes {@code In Process} and the customer is thanked.
	 */
	READY,

	/**
	 * The claim needs more information: it goes back to {@code Pending Information}, asking for the ticked items.
	 */
	NEEDS_INFORMATION
}
