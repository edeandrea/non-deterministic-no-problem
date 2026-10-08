package org.parasol.intake.mailbox;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * Removes quoted reply text from a plain-text email body.
 * <p>
 *   Quoted lines start with {@code >}. An attribution line ("On &lt;date&gt;, &lt;name&gt; wrote:", possibly wrapped onto a
 *   second line) is removed too, but only when the next non-blank line is quoted, so a customer's own sentence that
 *   happens to start with "On" and end with "wrote:" is kept. Everything else is kept, wherever it is: Roundcube writes
 *   the reply below the quote by default, other clients above it.
 * </p>
 */
final class QuotedText {
	private static final Pattern LINE_BREAK = Pattern.compile("\\r?\\n");
	private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");
	private static final String QUOTE_PREFIX = ">";
	private static final String ATTRIBUTION_START = "On ";
	private static final String ATTRIBUTION_END = "wrote:";

	private QuotedText() {
	}

	static String strip(String text) {
		var lines = List.of(LINE_BREAK.split(text, -1));

		var kept = IntStream.range(0, lines.size())
			.filter(index -> !isQuoted(lines.get(index)) && !isAttribution(lines, index))
			.mapToObj(lines::get)
			.toList();

		return BLANK_LINES.matcher(String.join("\n", kept))
			.replaceAll("\n\n")
			.strip();
	}

	private static boolean isQuoted(String line) {
		return line.stripLeading().startsWith(QUOTE_PREFIX);
	}

	// A one-line attribution, either line of one wrapped onto two lines, followed by a quote
	private static boolean isAttribution(List<String> lines, int index) {
		var line = lines.get(index).strip();
		var previous = (index > 0) ? lines.get(index - 1).strip() : "";
		var next = (index < (lines.size() - 1)) ? lines.get(index + 1).strip() : "";

		var isOneLine = line.startsWith(ATTRIBUTION_START) && line.endsWith(ATTRIBUTION_END) && isFollowedByQuote(lines, index + 1);
		var isFirstOfTwo = line.startsWith(ATTRIBUTION_START) && !line.endsWith(ATTRIBUTION_END) && next.endsWith(ATTRIBUTION_END) && isFollowedByQuote(lines, index + 2);
		var isSecondOfTwo = line.endsWith(ATTRIBUTION_END) && previous.startsWith(ATTRIBUTION_START) && !previous.endsWith(ATTRIBUTION_END) && isFollowedByQuote(lines, index + 1);

		return isOneLine || isFirstOfTwo || isSecondOfTwo;
	}

	private static boolean isFollowedByQuote(List<String> lines, int from) {
		return lines.stream()
			.skip(from)
			.filter(line -> !line.isBlank())
			.findFirst()
			.map(QuotedText::isQuoted)
			.orElse(false);
	}
}