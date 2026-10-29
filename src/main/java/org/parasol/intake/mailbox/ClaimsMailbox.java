package org.parasol.intake.mailbox;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.mail.Flags;
import jakarta.mail.Flags.Flag;
import jakarta.mail.Folder;
import jakarta.mail.FolderClosedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.StoreClosedException;
import jakarta.mail.search.MessageIDTerm;

import org.parasol.intake.config.IntakeConfig;
import org.parasol.intake.observability.MailClientSpan;
import org.parasol.intake.observability.MailClientSpan.Protocol;

import io.quarkus.logging.Log;

import io.opentelemetry.instrumentation.annotations.SpanAttribute;

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
	private static final String FOLDER = "messaging.destination.name";
	private static final Duration IMAP_TIMEOUT = Duration.ofSeconds(10);
	private static final int FETCH_SIZE_BYTES = 1024 * 1024;

	private final IntakeConfig config;
	private final Session session = Session.getInstance(sessionProperties());
	private final MimeMessageParser parser;
	private final ReadWriteLock folderLock = new ReentrantReadWriteLock(true);

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
	@MailClientSpan(protocol = Protocol.IMAP, operation = "list")
	public List<InboundEmail> list(@SpanAttribute(FOLDER) MailFolder folder) {
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
	@MailClientSpan(protocol = Protocol.IMAP, operation = "find")
	public Optional<InboundEmail> find(@SpanAttribute(FOLDER) MailFolder folder, String messageId) {
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
	@MailClientSpan(protocol = Protocol.IMAP, operation = "move")
	public boolean move(String messageId, @SpanAttribute(FOLDER) MailFolder from, MailFolder to) {
		return withFolder(from, Folder.READ_WRITE, source -> moveMessages(source, search(source, messageId), to))
			.orElse(false);
	}

	/**
	 * Moves the oldest email of a folder to another, and returns it. Only one email is ever held in memory, so a large backlog
	 * can be worked through, and emails come out in the order they arrived.
	 * <p>
	 *   An email that can't be read is moved to {@link MailFolder#FAILED} instead, and logged, and the next one is tried, so an
	 *   empty result always means the folder is empty. Losing the connection throws.
	 * </p>
	 *
	 * @param from The folder to take the email from
	 * @param to The folder to move it to
	 * @return The email, or empty if {@code from} is empty (or doesn't exist)
	 * @throws MailboxException if the connection to the mailbox fails
	 */
	@MailClientSpan(protocol = Protocol.IMAP, operation = "moveOldest")
	public Optional<InboundEmail> moveOldest(@SpanAttribute(FOLDER) MailFolder from, MailFolder to) {
		return withFolder(from, Folder.READ_WRITE, source -> moveOldest(source, to))
			.flatMap(Function.identity());
	}

	private Optional<InboundEmail> moveOldest(Folder source, MailFolder to) throws MessagingException {
		while (source.getMessageCount() > 0) {
			var oldest = source.getMessage(1);
			var email = tryParse(oldest);

			moveMessages(source, new Message[] { oldest }, email.isPresent() ? to : MailFolder.FAILED);
			source.expunge();

			if (email.isPresent()) {
				return email;
			}
		}

		return Optional.empty();
	}

	// A dropped connection is the caller's to handle; anything else means this one message is bad
	private Optional<InboundEmail> tryParse(Message message) throws MessagingException {
		try {
			return Optional.of(this.parser.parse(message));
		}
		catch (FolderClosedException | StoreClosedException e) {
			throw e;
		}
		catch (MessagingException | IOException | RuntimeException e) {
			Log.errorf(e, "Couldn't read the oldest email of the claims mailbox, moving it to the %s folder", folderName(MailFolder.FAILED));

			return Optional.empty();
		}
	}

	/**
	 * Moves every email without a {@code Message-ID} from one folder to another. Such an email can't be found by id, so this
	 * is how the intake files it. Real mail clients always set one; only hand-written SMTP scripts don't.
	 *
	 * @param from The folder the emails are in
	 * @param to The folder to move them to
	 * @return {@code true} if any email was moved
	 */
	@MailClientSpan(protocol = Protocol.IMAP, operation = "moveWithoutMessageId")
	public boolean moveWithoutMessageId(@SpanAttribute(FOLDER) MailFolder from, MailFolder to) {
		return withFolder(from, Folder.READ_WRITE, source -> moveMessages(source, withoutMessageId(source), to))
			.orElse(false);
	}

	private static Message[] withoutMessageId(Folder folder) throws MessagingException {
		return Arrays.stream(folder.getMessages())
			.filter(message -> !hasAnyMessageId(message))
			.toArray(Message[]::new);
	}

	private static boolean hasAnyMessageId(Message message) {
		try {
			return Optional.ofNullable(message.getHeader("Message-ID"))
				.stream()
				.flatMap(Arrays::stream)
				.anyMatch(header -> !header.isBlank());
		}
		catch (MessagingException e) {
			throw new MailboxException("Couldn't read the Message-ID of a message", e);
		}
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

	// Concurrent runs hitting GreenMail together stall some of their moves, so a call that changes the mailbox has it to itself, while
	// reads share it. The lock is fair, so a steady stream of reads can't starve a move
	private <T> Optional<T> withFolder(MailFolder folder, int mode, FolderAction<T> action) {
		var lock = (mode == Folder.READ_ONLY) ? this.folderLock.readLock() : this.folderLock.writeLock();
		lock.lock();

		try (var store = connect()) {
			var imapFolder = store.getFolder(folderName(folder));

			return imapFolder.exists() ? Optional.of(openAndRun(imapFolder, mode, action)) : Optional.empty();
		}
		catch (MessagingException | IOException e) {
			var message = "Couldn't access the %s folder of %s".formatted(folderName(folder), this.config.imap().user());

			throw (mode == Folder.READ_ONLY) ? new MailboxException(message, e) : new MailboxMoveException(message, e);
		}
		finally {
			lock.unlock();
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
		return folder.folderName(this.config.folders());
	}

	// Jakarta Mail has no timeouts by default, so a stalled server would hang the caller forever.
	// It also fetches a part over IMAP in 16 KB chunks, one round trip each: a 10 MB photo (the default size limit) is
	// ~640 round trips. ClaimsMailboxTests' oversized-image test took 4.6 s locally and timed out after 30 s on a CI
	// runner; with 1 MB chunks it takes 0.5 s. Partial fetch stays on (mail.imap.partialfetch=false is a bit faster),
	// because turning it off loads each attachment into memory whole, and the parser reads at most the size limit +
	// 1 byte, so an oversized attachment costs only ~11 chunks
	private static Properties sessionProperties() {
		// Jakarta Mail session properties are strings, with timeouts in milliseconds
		var timeoutMillis = String.valueOf(IMAP_TIMEOUT.toMillis());
		var properties = new Properties();
		properties.setProperty("mail.imap.connectiontimeout", timeoutMillis);
		properties.setProperty("mail.imap.timeout", timeoutMillis);
		properties.setProperty("mail.imap.fetchsize", String.valueOf(FETCH_SIZE_BYTES));

		return properties;
	}

	@FunctionalInterface
	private interface FolderAction<T> {
		T apply(Folder folder) throws MessagingException, IOException;
	}
}