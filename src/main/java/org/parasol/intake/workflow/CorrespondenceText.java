package org.parasol.intake.workflow;

import java.time.LocalDate;

/**
 * Builds the correspondence text: what's stored on the claim and what the agents read.
 */
public final class CorrespondenceText {
	static final String OMITTED_MARKER = "[Earlier correspondence omitted]";

	private CorrespondenceText() {
	}

	/**
	 * The claim body after a reply: the stored body, then the reply under a dated separator.
	 */
	public static String appendReply(String storedBody, LocalDate sentDate, String strippedReply) {
		return "%s\n\n----- Reply received %s -----\n\n%s".formatted(
			(storedBody == null) ? "" : storedBody.stripTrailing(),
			sentDate,
			strippedReply.strip());
	}

	/**
	 * The text of a first email for the agents.
	 */
	public static String firstEmail(String subject, String strippedBody) {
		return "Subject: %s\n\n%s".formatted(subject, strippedBody.strip());
	}

	/**
	 * Keeps the newest text within {@code limit} characters. Whole lines are kept where there's a line break to cut at, a
	 * single very long line is cut mid-line, and the {@link #OMITTED_MARKER} line in front counts toward the limit.
	 *
	 * @param text the correspondence
	 * @param limit the most characters to return
	 * @return the text unchanged if it fits, otherwise the marker line and the newest part of it
	 * @throws IllegalArgumentException if the limit has no room beyond the marker line
	 */
	public static String cap(String text, int limit) {
		var markerLine = OMITTED_MARKER + "\n";
		var room = limit - markerLine.length();

		if (room <= 0) {
			throw new IllegalArgumentException("A limit of %d characters leaves no room beyond the %d-character marker line".formatted(limit, markerLine.length()));
		}

		return (text.length() <= limit) ? text : markerLine + newestLines(text, room);
	}

	private static String newestLines(String text, int room) {
		var start = text.length() - room;
		var startsAtLineStart = (text.charAt(start - 1) == '\n');
		var lineBreak = text.indexOf('\n', start);
		var canCutForward = !startsAtLineStart && (lineBreak >= 0) && (lineBreak + 1 < text.length());

		return text.substring(canCutForward ? (lineBreak + 1) : start);
	}
}
