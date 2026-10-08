package org.parasol.intake.mailbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import jakarta.activation.DataHandler;
import jakarta.inject.Inject;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.parasol.claim.model.ClaimImageContentType;
import org.parasol.intake.IntakeConfig;
import org.parasol.intake.IntakeTestProfile;
import org.parasol.intake.mailbox.SkippedAttachment.Reason;
import org.parasol.testing.mail.GreenMailMailbox;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

// Sends real MIME messages over SMTP to the Compose GreenMail and reads them back through ClaimsMailbox over IMAP
@QuarkusTest
@TestProfile(IntakeTestProfile.class)
class ClaimsMailboxTests {
	private static final Duration WAIT_DURATION = Duration.ofSeconds(30);
	private static final String SENDER = "jane.doe@example.com";
	private static final String SENDER_NAME = "Jane Doe";
	private static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3 };
	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 4, 5, 6 };

	@Inject
	ClaimsMailbox claimsMailbox;

	@Inject
	GreenMailMailbox greenMail;

	@Inject
	IntakeConfig config;

	@ConfigProperty(name = "quarkus.mailer.host")
	String smtpHost;

	@ConfigProperty(name = "quarkus.mailer.port")
	int smtpPort;

	// Deleting the claims user also removes its folders, so every test starts with only an empty INBOX
	@BeforeEach
	void resetMailbox() {
		this.greenMail.deleteUser(this.config.address());
	}

	@Test
	void plainTextMessageIsParsed() throws Exception {
		var sentDate = Instant.parse("2026-10-08T14:00:00Z");
		var message = newMessage("My car was hit");
		message.setSentDate(Date.from(sentDate));
		message.setText("Someone backed into my car.\r\nIt's a red Honda.", "UTF-8");

		var messageId = send(message);

		assertThat(awaitInbox())
			.singleElement()
			.returns(Optional.of(messageId), InboundEmail::messageId)
			.returns(Optional.of(SENDER), InboundEmail::fromAddress)
			.returns(Optional.of(SENDER_NAME), InboundEmail::fromName)
			.returns("My car was hit", InboundEmail::subject)
			.returns(sentDate, InboundEmail::sentDate)
			.returns("Someone backed into my car.\nIt's a red Honda.", InboundEmail::body)
			.returns(List.of(), InboundEmail::referencedMessageIds)
			.returns(List.of(), InboundEmail::images)
			.returns(List.of(), InboundEmail::skippedAttachments)
			.returns(false, InboundEmail::isAutoReply);
	}

	@Test
	void htmlOnlyMessageIsConvertedToText() throws Exception {
		var message = newMessage("HTML only");
		message.setContent("<html><body><p>My car was hit.</p><p>It's a <b>red</b> Honda.<br>Plate ABC-123</p></body></html>", "text/html; charset=UTF-8");
		send(message);

		assertThat(awaitInbox())
			.singleElement()
			.extracting(InboundEmail::body)
			.isEqualTo("""
				My car was hit.

				It's a red Honda.
				Plate ABC-123""");
	}

	@Test
	void multipartAlternativePrefersTheTextPart() throws Exception {
		var alternative = new MimeMultipart("alternative");
		alternative.addBodyPart(textPart("The plain text version", "plain"));
		alternative.addBodyPart(textPart("<p>The <b>HTML</b> version</p>", "html"));

		var message = newMessage("Both versions");
		message.setContent(alternative);
		send(message);

		assertThat(awaitInbox())
			.singleElement()
			.extracting(InboundEmail::body)
			.isEqualTo("The plain text version");
	}

	@Test
	void imageAttachmentsAreExtractedAndOthersSkipped() throws Exception {
		var alternative = new MimeMultipart("alternative");
		alternative.addBodyPart(textPart("Photos attached", "plain"));
		alternative.addBodyPart(textPart("<p>Photos attached</p>", "html"));
		var body = new MimeBodyPart();
		body.setContent(alternative);

		var mixed = new MimeMultipart("mixed");
		mixed.addBodyPart(body);
		mixed.addBodyPart(attachment("front.jpg", "image/jpeg", JPEG));
		mixed.addBodyPart(attachment("side.png", "image/png", PNG));
		mixed.addBodyPart(attachment("police-report.pdf", "application/pdf", new byte[] { '%', 'P', 'D', 'F' }));
		mixed.addBodyPart(attachment("drawing.svg", "image/svg+xml", "<svg/>".getBytes()));
		// A text file sent as an attachment is skipped, not merged into the body
		mixed.addBodyPart(attachment("notes.txt", "text/plain", "Not part of the body".getBytes()));

		var message = newMessage("Photos");
		message.setContent(mixed);
		send(message);

		assertThat(awaitInbox())
			.singleElement()
			.satisfies(email -> assertThat(email.body()).isEqualTo("Photos attached"))
			.satisfies(email -> assertThat(email.images())
				.containsExactly(
					new ImageAttachment("front.jpg", ClaimImageContentType.JPEG, JPEG),
					new ImageAttachment("side.png", ClaimImageContentType.PNG, PNG)
				))
			.extracting(InboundEmail::skippedAttachments)
			.isEqualTo(List.of(
				new SkippedAttachment("police-report.pdf", "application/pdf", Reason.NOT_AN_IMAGE),
				new SkippedAttachment("drawing.svg", "image/svg+xml", Reason.NOT_AN_IMAGE),
				new SkippedAttachment("notes.txt", "text/plain", Reason.NOT_AN_IMAGE)
			));
	}

	@Test
	void oversizedExtraAndEmptyImagesAreSkipped() throws Exception {
		var maxImageBytes = (int) this.config.limits().maxImageSize().asLongValue();
		var maxImages = this.config.limits().maxImages();

		var mixed = new MimeMultipart("mixed");
		mixed.addBodyPart(textPart("Lots of photos", "plain"));
		mixed.addBodyPart(attachment("huge.jpg", "image/jpeg", new byte[maxImageBytes + 1]));
		mixed.addBodyPart(attachment("empty.png", "image/png", new byte[0]));

		for (var index = 1; index <= (maxImages + 1); index++) {
			mixed.addBodyPart(attachment("photo-%d.jpg".formatted(index), "image/jpeg", JPEG));
		}

		var message = newMessage("Too many photos");
		message.setContent(mixed);
		send(message);

		assertThat(awaitInbox())
			.singleElement()
			.satisfies(email -> assertThat(email.images())
				.hasSize(maxImages)
				.extracting(ImageAttachment::fileName)
				.startsWith("photo-1.jpg")
				.doesNotContain("photo-%d.jpg".formatted(maxImages + 1)))
			.extracting(InboundEmail::skippedAttachments)
			.isEqualTo(List.of(
				new SkippedAttachment("huge.jpg", "image/jpeg", Reason.TOO_LARGE),
				new SkippedAttachment("empty.png", "image/png", Reason.EMPTY),
				new SkippedAttachment("photo-%d.jpg".formatted(maxImages + 1), "image/jpeg", Reason.TOO_MANY)
			));
	}

	@Test
	void autoReplyHeadersAreDetected() throws Exception {
		var autoSubmitted = newMessage("Out of office");
		autoSubmitted.setText("I'm away");
		autoSubmitted.setHeader("Auto-Submitted", "auto-replied; owner-email=\"jane.doe@example.com\"");

		var bulk = newMessage("Newsletter");
		bulk.setText("News");
		bulk.setHeader("Precedence", "bulk");

		var explicitNo = newMessage("A person");
		explicitNo.setText("Hello");
		explicitNo.setHeader("Auto-Submitted", "no");

		send(autoSubmitted);
		send(bulk);
		send(explicitNo);

		assertThat(awaitInbox(3))
			.extracting(InboundEmail::subject, InboundEmail::isAutoReply)
			.containsExactlyInAnyOrder(
				tuple("Out of office", true),
				tuple("Newsletter", true),
				tuple("A person", false)
			);
	}

	@Test
	void quotedReplyTextIsStripped() throws Exception {
		var message = newMessage("Re: [CLM01000000] Your claim");
		message.setText("""
			It happened on Main Street.

			On Thu, Oct 8, 2026 at 10:00 AM Parasol Claims <claims@parasol.com> wrote:
			> Please tell us where the accident happened.
			""");
		message.setHeader("In-Reply-To", "<reply-1@parasol.com>");
		message.setHeader("References", "<first@parasol.com> <reply-1@parasol.com>");
		send(message);

		assertThat(awaitInbox())
			.singleElement()
			.satisfies(email -> assertThat(email.body()).contains("> Please tell us where the accident happened."))
			.returns("It happened on Main Street.", InboundEmail::strippedBody)
			.returns(List.of("<reply-1@parasol.com>", "<first@parasol.com>"), InboundEmail::referencedMessageIds);
	}

	@Test
	void messageIsFoundByItsMessageId() throws Exception {
		var first = newMessage("First");
		first.setText("one");
		var second = newMessage("Second");
		second.setText("two");

		send(first);
		var secondId = send(second);
		awaitInbox(2);

		assertThat(this.claimsMailbox.find(MailFolder.INBOX, secondId))
			.get()
			.returns("Second", InboundEmail::subject);

		// SEARCH HEADER matches substrings: a prefix of a real Message-ID must not match
		assertThat(this.claimsMailbox.find(MailFolder.INBOX, secondId.substring(0, secondId.length() - 2)))
			.isEmpty();

		assertThat(this.claimsMailbox.find(MailFolder.INBOX, "<missing@example.com>"))
			.isEmpty();

		assertThat(this.claimsMailbox.find(MailFolder.PROCESSED, secondId))
			.isEmpty();
	}

	@Test
	void movingRemovesTheMessageFromTheInboxAndCreatesTheFolder() throws Exception {
		var stays = newMessage("Stays");
		stays.setText("stays in the INBOX");
		var moves = newMessage("Moves");
		moves.setText("moves to processing, then processed");

		send(stays);
		var movingId = send(moves);
		awaitInbox(2);

		assertThat(this.claimsMailbox.list(MailFolder.PROCESSING))
			.isEmpty();

		assertThat(this.claimsMailbox.move(movingId, MailFolder.INBOX, MailFolder.PROCESSING))
			.isTrue();

		assertThat(this.claimsMailbox.unprocessed())
			.extracting(InboundEmail::subject)
			.containsExactly("Stays");

		assertThat(this.claimsMailbox.list(MailFolder.PROCESSING))
			.extracting(InboundEmail::messageId)
			.containsExactly(Optional.of(movingId));

		assertThat(this.claimsMailbox.move(movingId, MailFolder.PROCESSING, MailFolder.PROCESSED))
			.isTrue();

		assertThat(this.claimsMailbox.list(MailFolder.PROCESSING))
			.isEmpty();

		assertThat(this.claimsMailbox.find(MailFolder.PROCESSED, movingId))
			.get()
			.returns("Moves", InboundEmail::subject);

		// Already moved: nothing left to move
		assertThat(this.claimsMailbox.move(movingId, MailFolder.INBOX, MailFolder.PROCESSING))
			.isFalse();
	}

	private List<InboundEmail> awaitInbox() {
		return awaitInbox(1);
	}

	private List<InboundEmail> awaitInbox(int count) {
		return await()
			.atMost(WAIT_DURATION)
			.until(this.claimsMailbox::unprocessed, emails -> emails.size() >= count);
	}

	private MimeMessage newMessage(String subject) throws MessagingException, UnsupportedEncodingException {
		var message = new MimeMessage(Session.getInstance(new Properties()));
		message.setFrom(new InternetAddress(SENDER, SENDER_NAME));
		message.setRecipient(RecipientType.TO, new InternetAddress(this.config.address()));
		message.setSubject(subject);
		message.setSentDate(Date.from(Instant.now()));

		return message;
	}

	// A unique Message-ID per message, so tests never see each other's mail even if a delete were missed
	private String send(MimeMessage message) throws MessagingException {
		var messageId = "<%s@example.com>".formatted(UUID.randomUUID());
		message.saveChanges();
		message.setHeader("Message-ID", messageId);

		var properties = new Properties();
		properties.setProperty("mail.smtp.host", this.smtpHost);
		properties.setProperty("mail.smtp.port", String.valueOf(this.smtpPort));

		try (var transport = Session.getInstance(properties).getTransport("smtp")) {
			transport.connect();
			transport.sendMessage(message, message.getAllRecipients());
		}

		return messageId;
	}

	private static MimeBodyPart textPart(String text, String subtype) throws MessagingException {
		var part = new MimeBodyPart();
		part.setText(text, "UTF-8", subtype);

		return part;
	}

	private static MimeBodyPart attachment(String fileName, String contentType, byte[] data) throws MessagingException {
		var part = new MimeBodyPart();
		part.setDataHandler(new DataHandler(new ByteArrayDataSource(data, contentType)));
		part.setFileName(fileName);
		part.setDisposition(MimeBodyPart.ATTACHMENT);

		return part;
	}
}