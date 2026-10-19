package org.parasol.intake.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class CorrespondenceTextTests {
	private static final String MARKER_LINE = CorrespondenceText.OMITTED_MARKER + "\n";

	@Test
	void textThatFitsIsSentUnchanged() {
		var text = "line one of the thread\nline two of the thread, long enough to exceed the marker line";

		assertThat(CorrespondenceText.cap(text, text.length())).isEqualTo(text);
		assertThat(CorrespondenceText.cap(text, 10_000)).isEqualTo(text);
	}

	@Test
	void longTextKeepsTheNewestWholeLinesWithinTheLimit() {
		var text = "first line of the thread\nsecond line of the thread\nthird line\nfourth line";
		var limit = MARKER_LINE.length() + "third line\nfourth line".length() + 3;

		var capped = CorrespondenceText.cap(text, limit);

		assertThat(capped)
			.isEqualTo(MARKER_LINE + "third line\nfourth line")
			.hasSizeLessThanOrEqualTo(limit);
	}

	@Test
	void oneVeryLongLineIsCutMidLineRatherThanDropped() {
		var text = "x".repeat(200) + "END";
		var limit = MARKER_LINE.length() + 20;

		var capped = CorrespondenceText.cap(text, limit);

		assertThat(capped)
			.startsWith(MARKER_LINE)
			.endsWith("xxxEND")
			.hasSize(limit);
	}

	@Test
	void aLimitWithNoRoomBeyondTheMarkerIsRejectedWhateverTheText() {
		assertThatThrownBy(() -> CorrespondenceText.cap("hi", MARKER_LINE.length()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> CorrespondenceText.cap("some text", MARKER_LINE.length()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> CorrespondenceText.cap("some text", 5))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aReplyIsAppendedUnderADatedSeparator() {
		assertThat(CorrespondenceText.appendReply("The first email.\n", LocalDate.of(2026, 10, 11), "  The reply.  "))
			.isEqualTo("The first email.\n\n----- Reply received 2026-10-11 -----\n\nThe reply.");
	}
}
