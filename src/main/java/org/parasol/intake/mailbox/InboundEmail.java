package org.parasol.intake.mailbox;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An email read from the claims mailbox.
 *
 * @param messageId The {@code Message-ID} header, angle brackets included, if the email has one
 * @param referencedMessageIds The {@code Message-ID}s in the {@code In-Reply-To} and {@code References} headers, in that order, without duplicates
 * @param fromAddress The sender's address, if the {@code From} header has one
 * @param fromName The sender's display name, if the {@code From} header has one
 * @param subject The decoded subject, empty if there is none
 * @param sentDate The {@code Date} header, or the date the mailbox received the email if that's missing
 * @param body The text body. An HTML-only body is converted to text
 * @param strippedBody The text body without quoted reply text (lines starting with {@code >} and their "On … wrote:" line)
 * @param images The image attachments that are kept, in the order they appear
 * @param skippedAttachments The attachments that aren't kept, in the order they appear
 * @param isAutoReply Whether the {@code Auto-Submitted} or {@code Precedence} header marks the email as automatic
 */
public record InboundEmail(
	Optional<String> messageId,
	List<String> referencedMessageIds,
	Optional<String> fromAddress,
	Optional<String> fromName,
	String subject,
	Instant sentDate,
	String body,
	String strippedBody,
	List<ImageAttachment> images,
	List<SkippedAttachment> skippedAttachments,
	boolean isAutoReply) {

	public InboundEmail {
		Objects.requireNonNull(messageId, "messageId");
		referencedMessageIds = List.copyOf(referencedMessageIds);
		Objects.requireNonNull(fromAddress, "fromAddress");
		Objects.requireNonNull(fromName, "fromName");
		Objects.requireNonNull(subject, "subject");
		Objects.requireNonNull(sentDate, "sentDate");
		Objects.requireNonNull(body, "body");
		Objects.requireNonNull(strippedBody, "strippedBody");
		images = List.copyOf(images);
		skippedAttachments = List.copyOf(skippedAttachments);
	}
}