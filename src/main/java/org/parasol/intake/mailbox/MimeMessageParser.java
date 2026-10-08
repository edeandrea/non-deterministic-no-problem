package org.parasol.intake.mailbox;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeUtility;

import org.parasol.claim.model.ClaimImageContentType;
import org.parasol.intake.mailbox.SkippedAttachment.Reason;

/**
 * Turns a Jakarta Mail {@link Message} into an {@link InboundEmail}.
 * <p>
 *   Walks the MIME tree: in {@code multipart/alternative} the plain-text part wins over the HTML one, and in other
 *   multiparts the text parts are joined. Every other part (including inline images) is an attachment. Only the
 *   formats in the {@link ClaimImageContentType} allow-list count as images, judged by the declared media type alone.
 * </p>
 */
final class MimeMessageParser {
	private static final Pattern MESSAGE_ID = Pattern.compile("<[^<>\\s]+>");
	private static final Pattern LINE_BREAK = Pattern.compile("\\r\\n?");
	private static final Set<String> AUTOMATIC_PRECEDENCE = Set.of("bulk", "auto_reply", "junk");
	private static final String TEXT_PLAIN = "text/plain";
	private static final String TEXT_HTML = "text/html";
	private static final String ALTERNATIVE = "multipart/alternative";
	private static final String MULTIPART = "multipart/";
	private static final String TEXT_SEPARATOR = "\n\n";

	private final int maxImages;
	private final long maxImageBytes;

	MimeMessageParser(int maxImages, long maxImageBytes) {
		this.maxImages = maxImages;
		this.maxImageBytes = maxImageBytes;
	}

	InboundEmail parse(Message message) throws MessagingException, IOException {
		var parts = mimeParts(message);
		var body = parts.text()
			.or(() -> parts.html().map(HtmlText::toText))
			.map(MimeMessageParser::normalizeLineBreaks)
			.orElse("");

		var attachments = attachments(parts.attachments());
		var sender = firstAddress(message);

		return new InboundEmail(
			messageId(message),
			referencedMessageIds(message),
			sender.map(InternetAddress::getAddress),
			sender.map(InternetAddress::getPersonal)
				.filter(name -> !name.isBlank()),
			Optional.ofNullable(message.getSubject())
				.orElse(""),
			sentDate(message),
			body,
			QuotedText.strip(body),
			attachments.images(),
			attachments.skipped(),
			isAutoReply(message)
		);
	}

	private static MimeParts mimeParts(Part part) throws MessagingException, IOException {
		var isAttachment = Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition());

		// A text part sent as an attachment (e.g. notes.txt) is an attachment, never the body
		return isAttachment ? MimeParts.ofAttachment(part) : inlineParts(part);
	}

	// Only text and multipart content is read here: getContent() on an image part may try to decode it with AWT
	private static MimeParts inlineParts(Part part) throws MessagingException, IOException {
		return switch (baseType(part.getContentType())) {
			case TEXT_PLAIN -> MimeParts.ofText(text(part));
			case TEXT_HTML -> MimeParts.ofHtml(text(part));
			case ALTERNATIVE -> multipart(part, true);
			case String type when type.startsWith(MULTIPART) -> multipart(part, false);
			default -> MimeParts.ofAttachment(part);
		};
	}

	private static MimeParts multipart(Part part, boolean isAlternative) throws MessagingException, IOException {
		return (part.getContent() instanceof Multipart multipart) ? merge(bodyParts(multipart), isAlternative) : MimeParts.ofAttachment(part);
	}

	private static MimeParts merge(List<BodyPart> bodyParts, boolean isAlternative) throws MessagingException, IOException {
		var children = new ArrayList<MimeParts>();

		for (var bodyPart : bodyParts) {
			children.add(mimeParts(bodyPart));
		}

		var texts = children.stream()
			.flatMap(child -> child.text().stream())
			.toList();

		var htmls = children.stream()
			.flatMap(child -> child.html().stream())
			.toList();

		var attachments = children.stream()
			.flatMap(child -> child.attachments().stream())
			.toList();

		return isAlternative ?
		       new MimeParts(texts.stream().findFirst(), htmls.stream().findFirst(), attachments) :
		       new MimeParts(join(texts), join(htmls), attachments);
	}

	private Attachments attachments(List<Part> parts) throws MessagingException, IOException {
		var images = new ArrayList<ImageAttachment>();
		var skipped = new ArrayList<SkippedAttachment>();

		for (var part : parts) {
			var fileName = fileName(part, images.size() + skipped.size() + 1);
			var mediaType = baseType(part.getContentType());
			var contentType = ClaimImageContentType.find(mediaType);

			if (contentType.isEmpty()) {
				skipped.add(new SkippedAttachment(fileName, mediaType, Reason.NOT_AN_IMAGE));
			}
			else if (images.size() >= this.maxImages) {
				skipped.add(new SkippedAttachment(fileName, mediaType, Reason.TOO_MANY));
			}
			else {
				var data = readAtMost(part, this.maxImageBytes + 1);

				if (data.length == 0) {
					skipped.add(new SkippedAttachment(fileName, mediaType, Reason.EMPTY));
				}
				else if (data.length > this.maxImageBytes) {
					skipped.add(new SkippedAttachment(fileName, mediaType, Reason.TOO_LARGE));
				}
				else {
					images.add(new ImageAttachment(fileName, contentType.get(), data));
				}
			}
		}

		return new Attachments(images, skipped);
	}

	// Reads one byte past the limit at most, so an oversized attachment is detected without loading all of it
	private static byte[] readAtMost(Part part, long limit) throws MessagingException, IOException {
		try (InputStream input = part.getInputStream()) {
			return input.readNBytes((int) Math.min(limit, Integer.MAX_VALUE));
		}
	}

	private static String fileName(Part part, int position) throws MessagingException {
		return Optional.ofNullable(part.getFileName())
			.map(MimeMessageParser::decode)
			.map(String::strip)
			.filter(name -> !name.isEmpty())
			.orElseGet(() -> "attachment-%d".formatted(position));
	}

	private static String decode(String text) {
		try {
			return MimeUtility.decodeText(text);
		}
		catch (UnsupportedEncodingException e) {
			return text;
		}
	}

	private static String text(Part part) throws MessagingException, IOException {
		return switch (part.getContent()) {
			case String text -> text;
			case InputStream input -> {
				try (input) {
					yield new String(input.readAllBytes(), charset(part));
				}
			}
			case Object other -> other.toString();
		};
	}

	private static Charset charset(Part part) throws MessagingException {
		return Optional.ofNullable(new ContentType(part.getContentType()).getParameter("charset"))
			.map(MimeUtility::javaCharset)
			.filter(Charset::isSupported)
			.map(Charset::forName)
			.orElse(StandardCharsets.UTF_8);
	}

	private static List<String> referencedMessageIds(Message message) throws MessagingException {
		var inReplyTo = headers(message, "In-Reply-To");
		var references = headers(message, "References");

		return Stream.concat(inReplyTo.stream(), references.stream())
			.flatMap(value -> MESSAGE_ID.matcher(value).results())
			.map(MatchResult::group)
			.distinct()
			.toList();
	}

	private static boolean isAutoReply(Message message) throws MessagingException {
		var isAutoSubmitted = headers(message, "Auto-Submitted").stream()
			.map(MimeMessageParser::headerToken)
			.anyMatch(value -> !value.isEmpty() && !"no".equals(value));

		var hasAutomaticPrecedence = headers(message, "Precedence").stream()
			.map(MimeMessageParser::headerToken)
			.anyMatch(AUTOMATIC_PRECEDENCE::contains);

		return isAutoSubmitted || hasAutomaticPrecedence;
	}

	// "auto-replied; owner-email=..." -> "auto-replied"
	private static String headerToken(String value) {
		return value.split(";", 2)[0]
			.strip()
			.toLowerCase(Locale.ROOT);
	}

	// Jakarta Mail only exposes the Date header and the received date as java.util.Date (no java.time API, and its own
	// parser accepts forms DateTimeFormatter.RFC_1123_DATE_TIME rejects, e.g. a trailing "(EDT)" comment). Convert to an
	// Instant here, so nothing past the parser sees a Date
	private static Instant sentDate(Message message) throws MessagingException {
		return Optional.ofNullable(message.getSentDate())
			.or(() -> receivedDate(message))
			.map(Date::toInstant)
			.orElseGet(Instant::now);
	}

	private static Optional<Date> receivedDate(Message message) {
		try {
			return Optional.ofNullable(message.getReceivedDate());
		}
		catch (MessagingException e) {
			return Optional.empty();
		}
	}

	private static Optional<InternetAddress> firstAddress(Message message) throws MessagingException {
		return Optional.ofNullable(message.getFrom())
			.stream()
			.flatMap(Arrays::stream)
			.filter(InternetAddress.class::isInstance)
			.map(InternetAddress.class::cast)
			.findFirst();
	}

	private static Optional<String> messageId(Message message) throws MessagingException {
		return headers(message, "Message-ID").stream()
			.findFirst()
			.map(String::strip);
	}

	private static List<String> headers(Part part, String name) throws MessagingException {
		return Optional.ofNullable(part.getHeader(name))
			.map(List::of)
			.orElseGet(List::of);
	}

	private static List<BodyPart> bodyParts(Multipart multipart) throws MessagingException {
		var bodyParts = new ArrayList<BodyPart>();

		for (var index = 0; index < multipart.getCount(); index++) {
			bodyParts.add(multipart.getBodyPart(index));
		}

		return List.copyOf(bodyParts);
	}

	private static String baseType(String contentType) {
		return Optional.ofNullable(contentType)
			.map(type -> type.split(";", 2)[0])
			.map(String::strip)
			.map(type -> type.toLowerCase(Locale.ROOT))
			.orElse(TEXT_PLAIN);
	}

	private static Optional<String> join(List<String> texts) {
		return texts.isEmpty() ? Optional.empty() : Optional.of(String.join(TEXT_SEPARATOR, texts));
	}

	private static String normalizeLineBreaks(String text) {
		return LINE_BREAK.matcher(text)
			.replaceAll("\n")
			.strip();
	}

	private record MimeParts(Optional<String> text, Optional<String> html, List<Part> attachments) {
		static MimeParts ofText(String text) {
			return new MimeParts(Optional.of(text), Optional.empty(), List.of());
		}

		static MimeParts ofHtml(String html) {
			return new MimeParts(Optional.empty(), Optional.of(html), List.of());
		}

		static MimeParts ofAttachment(Part part) {
			return new MimeParts(Optional.empty(), Optional.empty(), List.of(part));
		}
	}

	private record Attachments(List<ImageAttachment> images, List<SkippedAttachment> skipped) {
	}
}