package org.parasol.intake.mailbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HtmlTextTests {
	@Test
	void blocksAndLineBreaksBecomeLines() {
		var html = """
			<html><head><title>Ignored</title><style>p { color: red; }</style></head>
			<body><p>My car was hit.</p><div>It's a <b>red</b> Honda.<br>Plate ABC-123</div><script>alert(1)</script></body></html>
			""";

		assertThat(HtmlText.toText(html))
			.isEqualTo("""
				My car was hit.

				It's a red Honda.
				Plate ABC-123""");
	}

	@Test
	void blockquotesBecomeQuotedLines() {
		var html = """
			<p>It was on Main Street.</p>
			<blockquote><p>Where did it happen?</p><blockquote>Earlier</blockquote></blockquote>
			""";

		assertThat(HtmlText.toText(html))
			.isEqualTo("""
				It was on Main Street.

				> Where did it happen?

				>> Earlier""");
	}

	@Test
	void quotedHtmlIsStrippedLikePlainText() {
		var html = "<p>It was on Main Street.</p><div>On Thu, Oct 8, 2026 Parasol wrote:</div><blockquote>Where?</blockquote>";

		assertThat(QuotedText.strip(HtmlText.toText(html)))
			.isEqualTo("It was on Main Street.");
	}

	// Non-breaking spaces become plain spaces too, which is what the LLM should see
	@Test
	void entitiesAreDecoded() {
		assertThat(HtmlText.toText("<p>Fish &amp; chips &lt;3&nbsp;yes</p>"))
			.isEqualTo("Fish & chips <3 yes");
	}
}