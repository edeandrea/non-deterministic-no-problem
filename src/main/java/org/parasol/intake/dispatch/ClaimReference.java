package org.parasol.intake.dispatch;

import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds the claim number an email names. The subject wins over the body, since it's what the reply subjects carry.
 */
final class ClaimReference {
	private static final Pattern CLAIM_NUMBER = Pattern.compile("\\bCLM\\d{8}\\b", Pattern.CASE_INSENSITIVE);

	private ClaimReference() {
	}

	static Optional<String> findClaimNumber(String subject, String body) {
		return Stream.of(subject, body)
			.map(ClaimReference::firstClaimNumber)
			.flatMap(Optional::stream)
			.findFirst();
	}

	private static Optional<String> firstClaimNumber(String text) {
		return Optional.ofNullable(text)
			.map(CLAIM_NUMBER::matcher)
			.filter(java.util.regex.Matcher::find)
			.map(matcher -> matcher.group().toUpperCase(java.util.Locale.ROOT));
	}
}
