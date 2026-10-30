package org.parasol.claim.model;

/**
 * Which way a {@link ClaimCorrespondence} email went.
 */
public enum CorrespondenceDirection {
	/**
	 * Received at the claims mailbox.
	 */
	INBOUND,

	/**
	 * A reply the intake sent about a claim. The intake generates its {@code Message-ID}, so it is known before the send.
	 */
	OUTBOUND
}
