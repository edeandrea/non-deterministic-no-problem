package org.parasol.testing.mail;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A message read from a GreenMail INBOX.
 *
 * @param from The sender's address
 * @param to The {@code To} recipients' addresses
 * @param subject The subject, empty if there is none
 * @param body The decoded text/plain body, exactly as received (line endings are typically {@code \r\n}). For a
 *   multipart message, its first text/plain part that isn't an attachment
 * @param headers Every header, keyed by its name in lower case (header names are case-insensitive), values in the order
 *   they appear
 */
public record ReceivedEmail(String from, List<String> to, String subject, String body, Map<String, List<String>> headers) {
	public ReceivedEmail {
		Objects.requireNonNull(from, "from");
		to = List.copyOf(to);
		Objects.requireNonNull(subject, "subject");
		Objects.requireNonNull(body, "body");
		// Copies the value lists too: Map.copyOf alone would keep the caller's mutable lists
		headers = headers.entrySet()
			.stream()
			.collect(Collectors.toUnmodifiableMap(Entry::getKey, entry -> List.copyOf(entry.getValue())));
	}

	/**
	 * The first value of a header.
	 *
	 * @param name The header name, in any case (e.g. {@code "In-Reply-To"})
	 * @return The header's first value, or empty if the message doesn't have it
	 */
	public Optional<String> firstHeader(String name) {
		return Optional.ofNullable(this.headers.get(name.toLowerCase(Locale.ROOT)))
			.flatMap(values -> values.stream().findFirst());
	}
}
