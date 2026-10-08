package org.parasol.intake.mailbox;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.mail.Flags;
import jakarta.mail.Flags.Flag;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.search.MessageIDTerm;

import org.parasol.intake.IntakeConfig;

/**
 * The claims mailbox, over IMAP.
 * <p>
 *   Each call opens its own connection and closes it before returning, so the bean holds no connection state. Messages
 *   are found by their {@code Message-ID}, which is how the intake refers to an email instead of carrying it around.
 *   Moving an email copies it, then deletes and expunges the original; a missing target folder is created first.
 * </p>
 */
@ApplicationScoped
public class ClaimsMailbox {
	private static final String INBOX = "INBOX";
	private static final String TIMEOUT_MILLIS = "10000";
	private static final String FETCH_SIZE_BYTES = String.valueOf(1024 * 1024);

	private final IntakeConfig config;
	private final Session session = Session.getInstance(sessionProperties());
	private final MimeMessageParser parser;

	ClaimsMailbox(IntakeConfig config) {
		this.config = config;
		this.parser = new MimeMessageParser(config.limits().maxImages(), config.limits().maxImageSize().asLongValue());
	}

	/**
	 * Reads every email waiting in the INBOX, oldest first.
	 *
	 * @return The unprocessed emails
	 */
	public List<InboundEmail> unprocessed() {
		return list(MailFolder.INBOX);
	}

	/**
	 * Reads every email in a folder, oldest first.
	 *
	 * @param folder The folder to read
	 * @return The emails, or an empty list if the folder doesn't exist yet
	 */
	public List<InboundEmail> list(MailFolder folder) {
		return withFolder(folder, Folder.READ_ONLY, imapFolder -> parseAll(imapFolder.getMessages()))
			.orElseGet(List::of);
	}

	/**
	 * Finds an email in a folder by its {@code Message-ID}.
	 *
	 * @param folder The folder to look in
	 * @param messageId The {@code Message-ID}, angle brackets included
	 * @return The email, or empty if the folder holds no email with that {@code Message-ID}
	 */
	public Optional<InboundEmail> find(MailFolder folder, String messageId) {
		return withFolder(folder, Folder.READ_ONLY, imapFolder -> parseAll(search(imapFolder, messageId)))
			.flatMap(emails -> emails.stream().findFirst());
	}

	/**
	 * Moves an email from one folder to another, creating the target folder if it's missing.
	 *
	 * @param messageId The {@code Message-ID}, angle brackets included
	 * @param from The folder the email is in
	 * @param to The folder to move it to
	 * @return {@code true} if the email was moved, {@code false} if {@code from} holds no email with that {@code Message-ID}
	 */
	public boolean move(String messageId, MailFolder from, MailFolder to) {
		return withFolder(from, Folder.READ_WRITE, source -> moveMessages(source, search(source, messageId), to))
			.orElse(false);
	}

	private boolean moveMessages(Folder source, Message[] messages, MailFolder to) throws MessagingException {
		var hasMessages = (messages.length > 0);

		if (hasMessages) {
			var target = source.getStore().getFolder(folderName(to));

			if (!target.exists()) {
				target.create(Folder.HOLDS_MESSAGES);
			}

			source.copyMessages(messages, target);
			source.setFlags(messages, new Flags(Flag.DELETED), true);
		}

		// Closing the source folder expunges the deleted originals
		return hasMessages;
	}

	// IMAP SEARCH HEADER matches substrings, so keep only the exact Message-ID
	private static Message[] search(Folder folder, String messageId) throws MessagingException {
		return Arrays.stream(folder.search(new MessageIDTerm(messageId)))
			.filter(message -> hasMessageId(message, messageId))
			.toArray(Message[]::new);
	}

	private static boolean hasMessageId(Message message, String messageId) {
		try {
			return Optional.ofNullable(message.getHeader("Message-ID"))
				.stream()
				.flatMap(Arrays::stream)
				.map(String::strip)
				.anyMatch(messageId::equals);
		}
		catch (MessagingException e) {
			throw new MailboxException("Couldn't read the Message-ID of a message", e);
		}
	}

	private List<InboundEmail> parseAll(Message[] messages) throws MessagingException, IOException {
		var emails = new ArrayList<InboundEmail>();

		for (var message : messages) {
			emails.add(this.parser.parse(message));
		}

		return List.copyOf(emails);
	}

	private <T> Optional<T> withFolder(MailFolder folder, int mode, FolderAction<T> action) {
		try (var store = connect()) {
			var imapFolder = store.getFolder(folderName(folder));

			return imapFolder.exists() ? Optional.of(openAndRun(imapFolder, mode, action)) : Optional.empty();
		}
		catch (MessagingException | IOException e) {
			throw new MailboxException("Couldn't access the %s folder of %s".formatted(folderName(folder), this.config.imap().user()), e);
		}
	}

	private static <T> T openAndRun(Folder folder, int mode, FolderAction<T> action) throws MessagingException, IOException {
		folder.open(mode);

		try (folder) {
			return action.apply(folder);
		}
	}

	private Store connect() throws MessagingException {
		var imap = this.config.imap();
		var store = this.session.getStore("imap");
		store.connect(imap.host(), imap.port(), imap.user(), imap.password().orElse(""));

		return store;
	}

	private String folderName(MailFolder folder) {
		var folders = this.config.folders();

		return switch (folder) {
			case INBOX -> INBOX;
			case PROCESSING -> folders.processing();
			case PROCESSED -> folders.processed();
			case FAILED -> folders.failed();
		};
	}

	// Jakarta Mail has no timeouts by default, so a stalled server would hang the caller forever.
	// It also fetches a part over IMAP in 16 KB chunks, one round trip each: a 10 MB photo (the default size limit) is
	// ~640 round trips. ClaimsMailboxTests' oversized-image test took 4.6 s locally and timed out after 30 s on a CI
	// runner; with 1 MB chunks it takes 0.5 s. Partial fetch stays on (mail.imap.partialfetch=false is a bit faster),
	// because turning it off loads each attachment into memory whole, and the parser reads at most the size limit +
	// 1 byte, so an oversized attachment costs only ~11 chunks
	private static Properties sessionProperties() {
		var properties = new Properties();
		properties.setProperty("mail.imap.connectiontimeout", TIMEOUT_MILLIS);
		properties.setProperty("mail.imap.timeout", TIMEOUT_MILLIS);
		properties.setProperty("mail.imap.fetchsize", FETCH_SIZE_BYTES);

		return properties;
	}

	@FunctionalInterface
	private interface FolderAction<T> {
		T apply(Folder folder) throws MessagingException, IOException;
	}
}