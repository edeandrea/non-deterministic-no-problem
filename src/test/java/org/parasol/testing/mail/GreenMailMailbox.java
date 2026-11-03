package org.parasol.testing.mail;

import static io.restassured.RestAssured.given;
import static java.util.function.Predicate.not;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Folder;
import jakarta.mail.Header;
import jakarta.mail.Message;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.restassured.specification.RequestSpecification;

/**
 * Test access to the GreenMail server from {@code compose-devservices.yml}.
 * <p>
 *   Mailboxes are read over IMAP, the way Roundcube reads them. GreenMail runs with auth disabled, so any password
 *   logs in and an unknown address is an empty INBOX. Purging and listing users go through the GreenMail REST API,
 *   because IMAP can only see one mailbox at a time.
 * </p>
 */
@ApplicationScoped
public class GreenMailMailbox {
	private static final String INBOX = "INBOX";
	private static final String ANY_PASSWORD = "any-password";
	private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
	private static final String PURGED = "Purged mails";
	private static final Duration IMAP_TIMEOUT = Duration.ofSeconds(5);

	private final String host;
	private final int imapPort;
	private final int apiPort;
	private final Session session = Session.getInstance(imapProperties());

	// The ports are mapped in from compose-devservices.yml by the io.quarkus.devservices.compose.config_map.port.* labels
	GreenMailMailbox(
		@ConfigProperty(name = "quarkus.mailer.host") String host,
		@ConfigProperty(name = "parasol.mail.imap-port") int imapPort,
		@ConfigProperty(name = "parasol.mail.greenmail-api-port") int apiPort) {

		this.host = host;
		this.imapPort = imapPort;
		this.apiPort = apiPort;
	}

	/**
	 * Reads every message in an address's INBOX, oldest first.
	 *
	 * @param address The mailbox's email address
	 * @return The messages, or an empty list if the mailbox has never received mail
	 */
	public List<ReceivedEmail> messages(String address) {
		try (var store = this.session.getStore("imap")) {
			store.connect(this.host, this.imapPort, address, ANY_PASSWORD);

			try (var inbox = store.getFolder(INBOX)) {
				inbox.open(Folder.READ_ONLY);

				// Read everything before the folder closes
				return Arrays.stream(inbox.getMessages())
					.map(GreenMailMailbox::toReceivedEmail)
					.toList();
			}
		}
		catch (MessagingException e) {
			throw new MailboxAccessException("Couldn't read the INBOX of %s".formatted(address), e);
		}
	}

	/**
	 * Reads the INBOX of every user GreenMail knows about (every address that has received mail or logged in).
	 *
	 * @return All messages in all mailboxes
	 */
	public List<ReceivedEmail> allMessages() {
		return users().stream()
			.map(this::messages)
			.flatMap(List::stream)
			.toList();
	}

	/**
	 * Waits until an address's INBOX holds at least one message.
	 *
	 * @param address The mailbox's email address
	 * @param atMost How long to wait
	 * @return The first (oldest) message in the INBOX
	 */
	public ReceivedEmail awaitMessage(String address, Duration atMost) {
		return await()
			.atMost(atMost)
			.pollInterval(POLL_INTERVAL)
			.until(() -> messages(address), not(List::isEmpty))
			.getFirst();
	}

	/**
	 * Deletes every message in every mailbox. The users themselves remain.
	 */
	public void purge() {
		var response = api().post("/api/mail/purge");
		var message = response.jsonPath().getString("message");

		// GreenMail answers 200 even when the purge fails ("Can not purge mails : ..."), so check the message too
		if ((response.statusCode() != 200) || !PURGED.equals(message)) {
			throw new MailboxAccessException("GreenMail purge failed (HTTP %d): %s".formatted(response.statusCode(), message));
		}
	}

	/**
	 * Deletes a user with all its folders and mail. An unknown address is fine: there's nothing to delete.
	 *
	 * @param address The user's email address
	 */
	public void deleteUser(String address) {
		var response = api().delete("/api/user/{address}", address);
		var isUnknown = (response.statusCode() == 400) && response.asString().contains("not found");

		if ((response.statusCode() != 200) && !isUnknown) {
			throw new MailboxAccessException("Couldn't delete GreenMail user %s (HTTP %d): %s".formatted(address, response.statusCode(), response.asString()));
		}
	}

	private List<String> users() {
		var response = api().get("/api/user");

		if (response.statusCode() != 200) {
			throw new MailboxAccessException("Couldn't list GreenMail users (HTTP %d): %s".formatted(response.statusCode(), response.asString()));
		}

		return response.jsonPath()
			.getList("email", String.class);
	}

	// Explicit base URI, port and base path, so the request doesn't inherit RestAssured's global defaults, which Quarkus
	// points at the app under test (including quarkus.http.root-path)
	private RequestSpecification api() {
		return given()
			.baseUri("http://%s".formatted(this.host))
			.port(this.apiPort)
			.basePath("/")
			.when();
	}

	// Fail fast instead of hanging an Awaitility poll if GreenMail stops answering (Jakarta Mail defaults to no timeout)
	private static Properties imapProperties() {
		var properties = new Properties();
		// Jakarta Mail session properties are strings, with timeouts in milliseconds
		var timeoutMillis = String.valueOf(IMAP_TIMEOUT.toMillis());
		properties.setProperty("mail.imap.connectiontimeout", timeoutMillis);
		properties.setProperty("mail.imap.timeout", timeoutMillis);

		return properties;
	}

	private static ReceivedEmail toReceivedEmail(Message message) {
		try {
			var from = addresses(message.getFrom())
				.findFirst()
				.orElse("");

			var to = addresses(message.getRecipients(RecipientType.TO)).toList();
			var subject = Optional.ofNullable(message.getSubject())
				.orElse("");

			return new ReceivedEmail(from, to, subject, textBody(message), headers(message));
		}
		catch (MessagingException e) {
			throw new MailboxAccessException("Couldn't read a message", e);
		}
	}

	// Header names are case-insensitive, so they're keyed in lower case
	private static Map<String, List<String>> headers(Message message) throws MessagingException {
		return Collections.list(message.getAllHeaders())
			.stream()
			.collect(Collectors.groupingBy(
				header -> header.getName().toLowerCase(Locale.ROOT),
				Collectors.mapping(Header::getValue, Collectors.toUnmodifiableList())
			));
	}

	private static Stream<String> addresses(Address[] addresses) {
		return Optional.ofNullable(addresses)
			.stream()
			.flatMap(Arrays::stream)
			.map(address -> (address instanceof InternetAddress internetAddress) ? internetAddress.getAddress() : address.toString());
	}

	// The text/plain body: the whole message (NotificationService's Mail.withText), or the first text/plain part of a
	// multipart one (the intake's Qute templates send multipart/alternative). Attachments are never the body
	private static String textBody(Message message) {
		return plainText(message)
			.orElseThrow(() -> new MailboxAccessException("Message has no text/plain body (%s)".formatted(contentType(message))));
	}

	private static Optional<String> plainText(Part part) {
		return Optional.of(part)
			.filter(not(GreenMailMailbox::isAttachment))
			.flatMap(p -> isPlainText(p) ? Optional.of(text(p)) : firstPlainText(bodyParts(p)));
	}

	private static Optional<String> firstPlainText(Stream<BodyPart> parts) {
		return parts.map(GreenMailMailbox::plainText)
			.flatMap(Optional::stream)
			.findFirst();
	}

	// Jakarta Mail's Part API throws checked exceptions, which streams can't, so these wrap them in MailboxAccessException

	private static Stream<BodyPart> bodyParts(Part part) {
		return (content(part) instanceof Multipart multipart) ?
		       IntStream.range(0, partCount(multipart)).mapToObj(index -> bodyPart(multipart, index)) :
		       Stream.empty();
	}

	private static boolean isAttachment(Part part) {
		try {
			return Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition());
		}
		catch (MessagingException e) {
			throw new MailboxAccessException("Couldn't read a part's disposition", e);
		}
	}

	private static boolean isPlainText(Part part) {
		try {
			return part.isMimeType("text/plain");
		}
		catch (MessagingException e) {
			throw new MailboxAccessException("Couldn't read a part's content type", e);
		}
	}

	private static String contentType(Part part) {
		try {
			return part.getContentType();
		}
		catch (MessagingException e) {
			throw new MailboxAccessException("Couldn't read a part's content type", e);
		}
	}

	private static Object content(Part part) {
		try {
			return part.getContent();
		}
		catch (MessagingException | IOException e) {
			throw new MailboxAccessException("Couldn't read a part's content", e);
		}
	}

	// Jakarta Mail decodes a text/plain part's content to a String with the part's charset
	private static String text(Part part) {
		return (String) content(part);
	}

	private static int partCount(Multipart multipart) {
		try {
			return multipart.getCount();
		}
		catch (MessagingException e) {
			throw new MailboxAccessException("Couldn't count a multipart's parts", e);
		}
	}

	private static BodyPart bodyPart(Multipart multipart, int index) {
		try {
			return multipart.getBodyPart(index);
		}
		catch (MessagingException e) {
			throw new MailboxAccessException("Couldn't read part %d of a multipart".formatted(index), e);
		}
	}
}
