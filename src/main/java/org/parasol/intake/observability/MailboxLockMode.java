package org.parasol.intake.observability;

import java.util.Locale;

/**
 * Which side of the claims mailbox's read/write lock a call waited for. The lower-case constant name is the {@code mode} tag of
 * {@code claim.intake.mailbox.lock.wait}.
 */
public enum MailboxLockMode {
	READ,
	WRITE;

	String tag() {
		return name().toLowerCase(Locale.ROOT);
	}
}
