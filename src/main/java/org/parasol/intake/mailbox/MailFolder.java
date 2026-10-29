package org.parasol.intake.mailbox;

import org.parasol.intake.config.IntakeConfig;

/**
 * The folders of the claims mailbox an email moves through. The names are configured in
 * {@link org.parasol.intake.config.IntakeConfig.Folders}, except the INBOX's.
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
	FAILED;

	/**
	 * The folder's name in the mailbox.
	 *
	 * @param folders The configured folder names
	 * @return The name, which for the INBOX is always {@code INBOX}
	 */
	String folderName(IntakeConfig.Folders folders) {
		return switch (this) {
			case INBOX -> "INBOX";
			case PROCESSING -> folders.processing();
			case PROCESSED -> folders.processed();
			case FAILED -> folders.failed();
		};
	}
}