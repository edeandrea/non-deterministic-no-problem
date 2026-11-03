package org.parasol.intake.mailbox;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;

import org.parasol.intake.config.IntakeConfig;
import org.parasol.intake.observability.IntakeActivities;
import org.parasol.intake.observability.IntakeActivity.WatcherConnectionChanged;
import org.parasol.intake.observability.IntakeActivity.WatcherReconnecting;

import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduler;
import io.quarkus.virtual.threads.VirtualThreads;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;

/**
 * Watches the claims INBOX with IMAP IDLE and hands every email over as an {@link EmailReceived} event, without waiting for
 * its run. A Mutiny pipeline on a virtual thread (IDLE blocks, so it mustn't hold a worker), started
 * only when the intake is enabled, and never blocking startup: if the mailbox is unreachable it keeps retrying, doubling its delay
 * up to a limit.
 * <p>
 *   The pipeline loops: connect; on the first connection of this JVM, hand over what a previous JVM left in the processing
 *   folder (where it is, oldest first, since it arrived before anything in the INBOX); move each email in the INBOX to the
 *   processing folder, oldest first, and hand it over; then IDLE until the INBOX changes. Moving it out of the INBOX is what
 *   stops a second pickup. The observer records the {@code Message-ID} and skips duplicates, so the watcher doesn't look at
 *   the email.
 * </p>
 * <p>
 *   There is one watcher, and the intake runs as a single replica: several watchers on one INBOX would race for the same
 *   message, and the per-sender queue is in memory. A failure of the connection, or of the mailbox, ends the connection and
 *   starts over; an observer's exception only costs that email its hand-over.
 * </p>
 */
@ApplicationScoped
class ClaimsInboxWatcher {
	private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(10);

	private final IntakeConfig config;
	private final ClaimsMailbox mailbox;
	private final Event<EmailReceived> received;
	private final Settings settings;
	private final Session session;
	private final Scheduler scheduler;
	private final ExecutorService executor;
	private final IntakeActivities activities;

	// Held while a blocking step runs, so stop() can wait for it. It guards no data
	private final ReentrantLock stepInProgress = new ReentrantLock();

	// Makes start() and stop() each atomic against the other. stop() holds it while it waits, so a start() can't begin a session
	// while the previous one drains
	private final ReentrantLock lifecycle = new ReentrantLock();

	private volatile boolean isStopping;
	private volatile boolean hasRecovered;
	private volatile InboxIdle idle;
	private volatile Cancellable subscription;

	ClaimsInboxWatcher(
		IntakeConfig config,
		ClaimsMailbox mailbox,
		Event<EmailReceived> received,
		Settings settings,
		Scheduler scheduler,
		@VirtualThreads ExecutorService executor,
		IntakeActivities activities
	) {
		this.activities = activities;
		this.config = config;
		this.mailbox = mailbox;
		this.received = received;
		this.settings = settings;
		this.scheduler = scheduler;
		this.session = session(settings.idleRefresh());
		this.executor = executor;
	}

	/**
	 * Where the watcher's IDLE connection goes and how long it waits. The scan and the moves always use
	 * {@link ClaimsMailbox}; tests point the IDLE connection at a proxy to cut it.
	 */
	record Settings(String host, int port, Duration idleRefresh, Duration reconnectDelay, Duration maxReconnectDelay) {
		static Settings from(IntakeConfig config) {
			var watcher = config.watcher();

			return new Settings(config.imap().host(), config.imap().port(), watcher.idleRefresh(), watcher.reconnectDelay(), watcher.maxReconnectDelay());
		}
	}

	// Static, so producing it doesn't need the watcher, which needs it
	@Produces
	static Settings settings(IntakeConfig config) {
		return Settings.from(config);
	}

	void onStart(@Observes StartupEvent event) {
		if (this.config.enabled()) {
			start();
		}
		else {
			Log.debug("The claims inbox watcher is disabled");
		}
	}

	void onShutdown(@Observes ShutdownEvent event) {
		stop();
	}

	/**
	 * Starts watching and returns at once.
	 */
	void start() {
		this.lifecycle.lock();

		try {
			if (this.subscription == null) {
				this.isStopping = false;
				this.hasRecovered = false;
				this.subscription = Multi.createBy()
				                         .repeating()
				                         .uni(this::session)
				                         .indefinitely()
				                         .runSubscriptionOn(this.executor)
				                         .subscribe()
				                         .with(_ -> { }, failure -> Log.error("The claims inbox watcher failed", failure));
				Log.infof("Watching the inbox of %s", this.config.imap().user());
			}
		}
		finally {
			this.lifecycle.unlock();
		}
	}

	/**
	 * Stops watching and waits for the work in progress, even if it's blocked in IDLE or waiting to reconnect.
	 */
	void stop() {
		this.lifecycle.lock();

		try {
			if (this.subscription != null) {
				this.isStopping = true;
				this.subscription.cancel();
				Optional.ofNullable(this.idle)
				        .ifPresent(InboxIdle::close);

				try {
					if (this.stepInProgress.tryLock(STOP_TIMEOUT.toMillis(), MILLISECONDS)) {
						this.stepInProgress.unlock();
					}
					else {
						Log.warn("The claims inbox watcher didn't stop in time");
					}
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}

				this.subscription = null;
			}
		}
		finally {
			this.lifecycle.unlock();
		}
	}

	boolean isRunning() {
		return this.subscription != null;
	}

	// One connection's life. Opening it (and the first scan) is retried with a backoff; once it's up, a failure starts a new
	// session after a pause, so the backoff starts over for a connection that worked
	private Uni<Void> session() {
		return Uni.createFrom().item(this::openAndScan)
			.onFailure(_ -> !this.isStopping)
				.invoke(failure -> {
					this.activities.publish(new WatcherReconnecting());
					Log.warnf(failure, "Can't reach the claims mailbox, retrying");
				})
			.onFailure(_ -> !this.isStopping)
				.retry().withBackOff(this.settings.reconnectDelay(), this.settings.maxReconnectDelay()).indefinitely()
			.invoke(this::watch)
			.replaceWithVoid()
			.onFailure().recoverWithUni(this::reconnectLater);
	}

	private InboxIdle openAndScan() {
		this.stepInProgress.lock();

		try {
			var connection = connect();

			try {
				recoverOnce();
				handOverInbox();

				return connection;
			}
			catch (RuntimeException e) {
				connection.close();

				throw e;
			}
		}
		finally {
			this.stepInProgress.unlock();
		}
	}

	private void watch(InboxIdle connection) {
		this.stepInProgress.lock();

		try (connection) {
			this.idle = connection;
			this.activities.publish(new WatcherConnectionChanged(true));

			while (!this.isStopping) {
				connection.awaitChange();
				handOverInbox();
			}
		}
		catch (MessagingException e) {
			throw new MailboxException("Lost the claims mailbox", e);
		}
		finally {
			this.idle = null;
			this.activities.publish(new WatcherConnectionChanged(false));
			this.stepInProgress.unlock();
		}
	}

	private Uni<Void> reconnectLater(Throwable failure) {
		if (this.isStopping) {
			return Uni.createFrom().voidItem();
		}

		this.activities.publish(new WatcherReconnecting());
		Log.warnf(failure, "Lost the claims mailbox, reconnecting in %s", this.settings.reconnectDelay());

		return Uni.createFrom().voidItem().onItem().delayIt().by(this.settings.reconnectDelay());
	}

	// The IDLE connection's own session. Its read timeout is twice the refresh interval, so a connection that goes silent is
	// still noticed, and it's built once because creating one loads the mail providers.
	private static Session session(Duration idleRefresh) {
		var properties = new Properties();
		properties.setProperty("mail.imap.connectiontimeout", String.valueOf(CONNECTION_TIMEOUT.toMillis()));
		properties.setProperty("mail.imap.timeout", String.valueOf(idleRefresh.multipliedBy(2).toMillis()));

		return Session.getInstance(properties);
	}

	private InboxIdle connect() {
		var imap = this.config.imap();

		try {
			return InboxIdle.open(this.session, this.scheduler, this.settings.host(), this.settings.port(), imap.user(), imap.password().orElse(""), this.settings.idleRefresh());
		}
		catch (MessagingException e) {
			throw new MailboxException("Can't connect to the claims mailbox", e);
		}
	}

	private void recoverOnce() {
		if (!this.hasRecovered) {
			handOverProcessing();
			this.hasRecovered = true;
		}
	}

	// What a previous JVM left in the processing folder arrived before anything in the INBOX, so it goes first, where it is
	private void handOverProcessing() {
		List<InboundEmail> leftOver = this.mailbox.list(MailFolder.PROCESSING);

		if (!leftOver.isEmpty()) {
			Log.infof("Handing over %d email(s) left in the processing folder", leftOver.size());
		}

		leftOver.forEach(this::handOver);
	}

	private void handOverInbox() {
		var next = this.mailbox.moveOldest(MailFolder.INBOX, MailFolder.PROCESSING);

		while (next.isPresent() && !this.isStopping) {
			handOver(next.get());
			next = this.mailbox.moveOldest(MailFolder.INBOX, MailFolder.PROCESSING);
		}
	}

	private void handOver(InboundEmail email) {
		try {
			this.received.fire(new EmailReceived(email));
		}
		catch (RuntimeException e) {
			Log.errorf(e, "Couldn't hand over the email %s", email.messageId().orElse("without a Message-ID"));
		}
	}
}