package org.parasol.intake.mailbox;

import java.time.Duration;
import java.util.UUID;

import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;

import org.eclipse.angus.mail.imap.IMAPFolder;

import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import io.quarkus.scheduler.Scheduler;

/**
 * The watcher's own IMAP connection to the INBOX, used for {@code IDLE} and nothing else.
 * <p>
 *   It needs a session of its own, not {@link ClaimsMailbox}'s: that one has a short read timeout, and Angus Mail turns a read
 *   timeout during IDLE into a connection loss. A scheduled job ends each IDLE after the refresh interval (any command on the folder does that), so
 *   the caller issues a fresh one, as RFC 2177 asks.
 * </p>
 */
final class InboxIdle implements AutoCloseable {
	private final Store store;
	private final IMAPFolder inbox;
	private final Scheduler scheduler;
	private final String refreshJobId = "claims-inbox-idle-refresh-" + UUID.randomUUID();

	private InboxIdle(Store store, IMAPFolder inbox, Scheduler scheduler, Duration refresh) {
		this.store = store;
		this.inbox = inbox;
		this.scheduler = scheduler;

		// Ends an IDLE that has lasted a refresh interval. One that isn't in an IDLE just syncs the folder
		this.scheduler.newJob(this.refreshJobId)
		              .setDelayed(refresh.toString())
		              .setInterval(refresh.toString())
		              .setConcurrentExecution(ConcurrentExecution.SKIP)
		              .setTask(_ -> endIdle(), true)
		              .schedule();
	}

	/**
	 * Connects and opens the INBOX.
	 *
	 * @param session The session to connect with. Its read timeout must exceed {@code refresh}: Angus Mail turns a read timeout
	 *   during IDLE into a connection loss
	 * @param scheduler Runs the job that ends each IDLE after {@code refresh}
	 * @param host The IMAP host
	 * @param port The IMAP port
	 * @param user The login
	 * @param password The password, empty if there is none
	 * @param refresh How long an IDLE lasts before it's ended and issued again
	 * @return The open connection
	 * @throws MessagingException if it can't connect
	 */
	static InboxIdle open(Session session, Scheduler scheduler, String host, int port, String user, String password, Duration refresh) throws MessagingException {
		var store = session.getStore("imap");

		try {
			store.connect(host, port, user, password);
			var inbox = (IMAPFolder) store.getFolder("INBOX");
			inbox.open(Folder.READ_ONLY);

			return new InboxIdle(store, inbox, scheduler, refresh);
		}
		catch (MessagingException | RuntimeException e) {
			closeQuietly(store);
			throw e;
		}
	}

	/**
	 * Blocks until the INBOX reports a change or the refresh interval passes, whichever is first. Mail that arrived before
	 * the call is reported at once.
	 *
	 * @throws MessagingException if the connection is lost
	 */
	void awaitChange() throws MessagingException {
		// A server may hold back the news of mail that arrived since the last command until the next one, and an IDLE
		// started after that arrival wouldn't wake for it. Everything handed over has left the INBOX, so any count is news
		endIdle();

		if (this.inbox.getMessageCount() == 0) {
			this.inbox.idle(true);
		}
	}

	// Any command on the folder from another thread ends a blocked IDLE, and one outside an IDLE just syncs the folder
	private void endIdle() {
		// A connection that's already closed has nothing to end, and the loop that owns it finds out on its own
		if (this.inbox.isOpen()) {
			try {
				this.inbox.doCommand(protocol -> {
					protocol.noop();
					return null;
				});
			}
			catch (MessagingException e) {
				Log.debugf(e, "Couldn't send a NOOP; the connection is probably gone");
			}
		}
	}

	/**
	 * Closes the connection. It can be called from another thread, and ends an IDLE that's blocked.
	 */
	@Override
	public void close() {
		this.scheduler.unscheduleJob(this.refreshJobId);
		closeQuietly(this.store);
	}

	private static void closeQuietly(Store store) {
		try {
			store.close();
		}
		catch (MessagingException e) {
			Log.debugf(e, "Couldn't close the IDLE connection cleanly");
		}
	}
}
