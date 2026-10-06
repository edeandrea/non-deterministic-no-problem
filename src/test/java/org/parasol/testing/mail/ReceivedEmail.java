package org.parasol.testing.mail;

import java.util.List;

/**
 * A message read from a GreenMail INBOX.
 *
 * @param from The sender's address
 * @param to The {@code To} recipients' addresses
 * @param subject The subject
 * @param body The decoded text body, exactly as received (line endings are typically {@code \r\n})
 */
public record ReceivedEmail(String from, List<String> to, String subject, String body) {
	public ReceivedEmail {
		to = List.copyOf(to);
	}
}