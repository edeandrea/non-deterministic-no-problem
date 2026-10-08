package org.parasol.intake.mailbox;

/**
 * The folders of the claims mailbox an email moves through. The names are configured in
 * {@link org.parasol.intake.IntakeConfig.Folders}, except the INBOX's.
 */
public enum MailFolder {
	/**
	 * Where new email arrives.
	 */
	INBOX,

	/**
	 * Where an email waits while its intake run is working on it.
	 */
	PROCESSING,

	/**
	 * Where an email goes once it's handled.
	 */
	PROCESSED,

	/**
	 * Where an email goes when its intake run fails.
	 */
	FAILED
}