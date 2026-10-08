package org.parasol.intake.mailbox;

import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

/**
 * Converts an HTML email body to plain text.
 * <p>
 *   Block elements and {@code <br>} become line breaks, scripts and styles are dropped, and {@code <blockquote>} lines
 *   are prefixed with {@code >} (once per nesting level), so {@link QuotedText} strips quoted replies from HTML-only
 *   email the same way as from plain text.
 * </p>
 */
final class HtmlText {
	private static final Pattern TRAILING_SPACES = Pattern.compile("[ \\t]+\\n");
	private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");
	private static final String QUOTE_TAG = "blockquote";

	private HtmlText() {
	}

	static String toText(String html) {
		var document = Jsoup.parse(html);
		document.select("head, script, style, template").remove();

		var visitor = new TextVisitor();
		NodeTraversor.traverse(visitor, document.body());

		var text = TRAILING_SPACES.matcher(visitor.text())
			.replaceAll("\n");

		return BLANK_LINES.matcher(text)
			.replaceAll("\n\n")
			.strip();
	}

	private static final class TextVisitor implements NodeVisitor {
		private final StringBuilder text = new StringBuilder();
		private int quoteDepth;
		private boolean isLineStart = true;

		@Override
		public void head(Node node, int depth) {
			switch (node) {
				case TextNode textNode -> append(textNode.text());
				case Element element when element.nameIs("br") -> newLine();
				case Element element when element.nameIs(QUOTE_TAG) -> {
					newLine();
					this.quoteDepth++;
				}
				case Element element when element.isBlock() -> newLine();
				default -> {
				}
			}
		}

		@Override
		public void tail(Node node, int depth) {
			switch (node) {
				case Element element when element.nameIs(QUOTE_TAG) -> {
					newLine();
					this.quoteDepth--;
				}
				case Element element when element.isBlock() -> newLine();
				default -> {
				}
			}
		}

		String text() {
			return this.text.toString();
		}

		private void append(String value) {
			var content = this.isLineStart ? value.stripLeading() : value;

			if (!content.isEmpty()) {
				if (this.isLineStart && (this.quoteDepth > 0)) {
					this.text.repeat('>', this.quoteDepth)
						.append(' ');
				}

				this.text.append(content);
				this.isLineStart = false;
			}
		}

		private void newLine() {
			this.text.append('\n');
			this.isLineStart = true;
		}
	}
}