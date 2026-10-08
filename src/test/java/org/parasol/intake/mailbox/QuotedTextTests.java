package org.parasol.intake.mailbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QuotedTextTests {
	@Test
	void replyAboveTheQuoteKeepsOnlyTheReply() {
		var body = """
			The accident was on Main Street.

			On Thu, Oct 8, 2026 at 10:00 AM Parasol Claims <claims@parasol.com> wrote:
			> Please tell us where the accident happened.
			> Thanks
			""";

		assertThat(QuotedText.strip(body))
			.isEqualTo("The accident was on Main Street.");
	}

	@Test
	void replyBelowTheQuoteKeepsOnlyTheReply() {
		var body = """
			On 2026-10-08 10:00, claims@parasol.com wrote:
			> Please tell us where the accident happened.

			It was on Main Street.
			""";

		assertThat(QuotedText.strip(body))
			.isEqualTo("It was on Main Street.");
	}

	@Test
	void wrappedAttributionIsRemoved() {
		var body = """
			It was on Main Street.

			On Thu, Oct 8, 2026 at 10:00 AM Parasol Claims
			<claims@parasol.com> wrote:
			> Please tell us where the accident happened.
			""";

		assertThat(QuotedText.strip(body))
			.isEqualTo("It was on Main Street.");
	}

	@Test
	void nestedAndIndentedQuotesAreRemoved() {
		var body = """
			Answer
			  > quoted
			>> quoted twice
			""";

		assertThat(QuotedText.strip(body))
			.isEqualTo("Answer");
	}

	@Test
	void attributionLikeSentenceWithoutAQuoteIsKept() {
		var body = """
			On Monday my neighbour wrote:
			the car was parked outside.
			""";

		assertThat(QuotedText.strip(body))
			.isEqualTo(body.strip());
	}

	@Test
	void textWithoutQuotesIsUnchanged() {
		var body = "My car was hit.\r\n\r\nIt's a red Honda.";

		assertThat(QuotedText.strip(body))
			.isEqualTo("My car was hit.\n\nIt's a red Honda.");
	}

	@Test
	void onlyQuotedTextLeavesNothing() {
		assertThat(QuotedText.strip("> everything quoted\n> here"))
			.isEmpty();
	}
}