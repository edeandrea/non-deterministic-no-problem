package org.parasol.intake.reply;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.parasol.intake.mailbox.InboundEmail;

/**
 * The headers that thread a reply under the email it answers.
 *
 * @param inReplyTo The {@code Message-ID} of the email being answered, if it has one
 * @param references The answered email's own {@code References}, oldest first
 */
public record ThreadingHeaders(Optional<String> inReplyTo, List<String> references) {
	public ThreadingHeaders {
		Objects.requireNonNull(inReplyTo, "inReplyTo");
		references = List.copyOf(references);
	}

	/**
	 * Threads a reply under an inbound email.
	 *
	 * @param email The email being answered
	 * @return The threading headers for the reply
	 */
	public static ThreadingHeaders of(InboundEmail email) {
		return new ThreadingHeaders(email.messageId(), email.referencedMessageIds());
	}

	/**
	 * Threads a reply under a known {@code Message-ID}, with no earlier references.
	 *
	 * @param messageId The {@code Message-ID} of the email being answered
	 * @return The threading headers for the reply
	 */
	public static ThreadingHeaders replyingTo(String messageId) {
		return new ThreadingHeaders(Optional.of(messageId), List.of());
	}

	/**
	 * No threading headers.
	 *
	 * @return Headers that add nothing to the reply
	 */
	public static ThreadingHeaders none() {
		return new ThreadingHeaders(Optional.empty(), List.of());
	}
}
