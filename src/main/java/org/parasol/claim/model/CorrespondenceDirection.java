package org.parasol.claim.model;

/**
 * Which way a {@link ClaimCorrespondence} email went.
 */
public enum CorrespondenceDirection {
	/**
	 * Received at the claims mailbox. The only direction recorded today: the mailer generates the {@code Message-ID} of
	 * the replies, so they aren't known.
	 */
	INBOUND
}
