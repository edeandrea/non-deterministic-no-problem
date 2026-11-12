package org.parasol.intake.mailbox;

import java.util.Locale;
import java.util.Set;

/**
 * Recognizes mail the intake must never answer, so two automatic senders can't keep each other busy.
 */
public final class MailLoopGuard {
	// The mail system's own senders: bounces and delivery reports come from them
	private static final Set<String> MAIL_SYSTEM_SENDERS = Set.of("mailer-daemon", "postmaster");

	private MailLoopGuard() {
	}

	/**
	 * Whether an email was sent automatically (see {@link InboundEmail#isAutomatic()}), by the mail system itself
	 * ({@code MAILER-DAEMON} or {@code postmaster}), or from the claims address itself.
	 */
	public static boolean shouldIgnore(InboundEmail email, String intakeAddress) {
		return email.isAutomatic() || isFromMailSystem(email) || isFromIntakeAddress(email, intakeAddress);
	}

	private static boolean isFromMailSystem(InboundEmail email) {
		return email.fromAddress()
			.map(address -> address.split("@", 2)[0])
			.map(localPart -> localPart.toLowerCase(Locale.ROOT))
			.filter(MAIL_SYSTEM_SENDERS::contains)
			.isPresent();
	}

	private static boolean isFromIntakeAddress(InboundEmail email, String intakeAddress) {
		return email.fromAddress()
			.filter(intakeAddress::equalsIgnoreCase)
			.isPresent();
	}
}