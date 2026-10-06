package org.parasol.testing.mail;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Predicate;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.mail.Address;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
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
	private static final String IMAP_TIMEOUT_MILLIS = "5000";

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
			.until(() -> messages(address), Predicate.not(List::isEmpty))
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
		properties.setProperty("mail.imap.connectiontimeout", IMAP_TIMEOUT_MILLIS);
		properties.setProperty("mail.imap.timeout", IMAP_TIMEOUT_MILLIS);

		return properties;
	}

	private static ReceivedEmail toReceivedEmail(Message message) {
		try {
			var from = addresses(message.getFrom())
				.findFirst()
				.orElse("");

			var to = addresses(message.getRecipients(RecipientType.TO)).toList();

			return new ReceivedEmail(from, to, message.getSubject(), textBody(message));
		}
		catch (MessagingException | IOException e) {
			throw new MailboxAccessException("Couldn't read a message", e);
		}
	}

	private static Stream<String> addresses(Address[] addresses) {
		return Optional.ofNullable(addresses)
			.stream()
			.flatMap(Arrays::stream)
			.map(address -> (address instanceof InternetAddress internetAddress) ? internetAddress.getAddress() : address.toString());
	}

	// The app sends plain text mail only (Mail.withText), so a multipart message is unexpected
	private static String textBody(Message message) throws MessagingException, IOException {
		return switch (message.getContent()) {
			case null -> throw new MailboxAccessException("Message has no content (%s)".formatted(message.getContentType()));
			case String text -> text;
			case Object other -> throw new MailboxAccessException(
				"Expected a text/plain message but got %s (%s)".formatted(message.getContentType(), other.getClass().getName())
			);
		};
	}
}