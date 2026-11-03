package org.parasol.intake.mailbox;


/**
 * Recognizes mail the intake must never answer, so two automatic senders can't keep each other busy.
 */
public final class MailLoopGuard {
	private MailLoopGuard() {
	}

	/**
	 * Whether an email is an auto-reply, or was sent from the claims address itself.
	 */
	public static boolean shouldIgnore(InboundEmail email, String intakeAddress) {
		return email.isAutoReply() || isFromIntakeAddress(email, intakeAddress);
	}

	private static boolean isFromIntakeAddress(InboundEmail email, String intakeAddress) {
		return email.fromAddress()
			.filter(intakeAddress::equalsIgnoreCase)
			.isPresent();
	}
}
