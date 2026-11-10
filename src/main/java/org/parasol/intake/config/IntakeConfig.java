package org.parasol.intake.config;

import java.time.Duration;
import java.util.Optional;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import io.quarkus.runtime.configuration.MemorySize;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration of the email claim intake: the claims mailbox it reads and the limits it applies to inbound email.
 */
@ConfigMapping(prefix = "parasol.intake")
public interface IntakeConfig {
	/**
	 * Whether the claims inbox is watched. Off in {@code %test}; only the tests that exercise the watcher turn it on.
	 *
	 * @return {@code true} if inbound claim email is processed
	 */
	@WithDefault("true")
	boolean enabled();

	/**
	 * The address customers send claims to. Replies are sent from it, and mail from it is never processed. A bare address, with no
	 * display name: replies' {@code Message-ID}s take their domain from it.
	 *
	 * @return the claims inbox address
	 */
	@WithDefault("claims@parasol.com")
	@NotBlank
	@Email
	String address();

	/**
	 * The IMAP connection to the claims mailbox.
	 *
	 * @return the IMAP settings
	 */
	Imap imap();

	/**
	 * The folders an email moves through after it leaves the INBOX.
	 *
	 * @return the folder names
	 */
	Folders folders();

	/**
	 * Limits applied to inbound email.
	 *
	 * @return the limits
	 */
	Limits limits();

	/**
	 * How the claims inbox is watched.
	 *
	 * @return the watcher timings
	 */
	Watcher watcher();

	/**
	 * How the intake's metrics are measured, for those that can't be read from an event.
	 *
	 * @return the metrics settings
	 */
	Metrics metrics();

	/**
	 * How signals for intake runs (a reviewer's decision) are delivered.
	 *
	 * @return the signal settings
	 */
	Signals signals();

	/**
	 * How the application instance that runs the intake is chosen.
	 *
	 * @return the leadership settings
	 */
	Leadership leadership();

	/**
	 * The IMAP connection to the claims mailbox.
	 */
	interface Imap {
		/**
		 * The IMAP host. Defaults to the mail host the app sends through (GreenMail).
		 *
		 * @return the host name
		 */
		@WithDefault("${quarkus.mailer.host}")
		String host();

		/**
		 * The IMAP port. Compose Dev Services map GreenMail's IMAP port into {@code parasol.mail.imap-port} in dev and
		 * test; elsewhere it falls back to GreenMail's own port, 3143.
		 *
		 * @return the port
		 */
		@WithDefault("${parasol.mail.imap-port:3143}")
		int port();

		/**
		 * The IMAP login. Defaults to the claims inbox address.
		 *
		 * @return the user name
		 */
		@WithDefault("${parasol.intake.address}")
		String user();

		/**
		 * The IMAP password. GreenMail runs with authentication disabled, so none is needed there.
		 *
		 * @return the password, if one is configured
		 */
		Optional<String> password();
	}

	/**
	 * The folders an email moves through after it leaves the INBOX. Missing folders are created on first use.
	 */
	interface Folders {
		/**
		 * Where an email waits while its intake run is working on it.
		 *
		 * @return the folder name
		 */
		@WithDefault("Processing")
		String processing();

		/**
		 * Where an email goes once it's handled.
		 *
		 * @return the folder name
		 */
		@WithDefault("Processed")
		String processed();

		/**
		 * Where an email goes when its intake run fails.
		 *
		 * @return the folder name
		 */
		@WithDefault("Failed")
		String failed();
	}

	/**
	 * Limits applied to inbound email.
	 */
	interface Limits {
		/**
		 * The most characters of correspondence sent to the LLM. The stored body is never truncated.
		 *
		 * @return the character limit
		 */
		@WithDefault("20000")
		@Positive
		int maxBodyCharacters();

		/**
		 * The largest image attachment that's kept. Larger ones are skipped and mentioned in the reply.
		 *
		 * @return the size limit
		 */
		@WithDefault("10M")
		MemorySize maxImageSize();

		/**
		 * The most image attachments kept from one email. Any beyond it are skipped and mentioned in the reply.
		 *
		 * @return the count limit
		 */
		@WithDefault("10")
		@Positive
		int maxImages();
	}

	/**
	 * Timings of the IMAP IDLE watcher.
	 */
	interface Watcher {
		/**
		 * How often the watcher ends its IMAP IDLE and issues it again (RFC 2177 asks for at most every 29 minutes). The
		 * IDLE connection's read timeout is twice this, so a connection that goes silent is noticed.
		 *
		 * @return the refresh interval
		 */
		@WithDefault("1m")
		Duration idleRefresh();

		/**
		 * How long the watcher waits before its first attempt to reconnect. It doubles on every failure up to
		 * {@link #maxReconnectDelay()}.
		 *
		 * @return the first delay
		 */
		@WithDefault("1s")
		Duration reconnectDelay();

		/**
		 * The longest the watcher waits between attempts to reconnect.
		 *
		 * @return the longest delay
		 */
		@WithDefault("1m")
		Duration maxReconnectDelay();
	}

	/**
	 * Settings of the intake's metrics.
	 */
	interface Metrics {
		/**
		 * How often the INBOX's message count is measured for {@code claim.intake.inbox.depth}. A measurement logs in to the mailbox,
		 * so it isn't a gauge read on every scrape.
		 *
		 * @return the measuring interval
		 */
		@WithDefault("30s")
		Duration inboxDepthInterval();
	}

	/**
	 * Settings of the delivery of signals to intake runs.
	 */
	interface Signals {
		/**
		 * How often pending signals are handed to the runs this application instance hosts. A signal is also tried once, right after it's written.
		 *
		 * @return the polling interval
		 */
		@WithDefault("1s")
		Duration pollInterval();
	}

	/**
	 * Which application instance is the intake leader: the only one that watches the inbox, dispatches emails and hosts the runs.
	 */
	interface Leadership {
		/**
		 * How the leader is chosen.
		 *
		 * @return the mode
		 */
		@WithDefault("SINGLE")
		Mode mode();

		/**
		 * The name of the Kubernetes {@code Lease} the instances compete for. Used only in {@link Mode#KUBERNETES_LEASE}.
		 *
		 * @return the Lease name
		 */
		@WithDefault("parasol-intake-leader")
		@NotBlank
		String leaseName();

		/**
		 * How long a Lease holder that stops renewing keeps it, so the longest a crashed leader delays its successor.
		 *
		 * @return the Lease duration
		 */
		@WithDefault("15s")
		Duration leaseDuration();

		/**
		 * How long the leader keeps trying to renew before it gives up leading. Shorter than {@link #leaseDuration()}.
		 *
		 * @return the renew deadline
		 */
		@WithDefault("10s")
		Duration renewDeadline();

		/**
		 * How often an instance tries to take or renew the Lease.
		 *
		 * @return the retry period
		 */
		@WithDefault("2s")
		Duration retryPeriod();

		/**
		 * The name this application instance competes under, which must differ between instances. Without one, the host name is used
		 * (the pod name on Kubernetes), and failing that a random one.
		 *
		 * @return the identity, if one is configured
		 */
		Optional<String> identity();
	}

	/**
	 * How the intake leader is chosen.
	 */
	enum Mode {
		/**
		 * This application instance is always the leader: one replica, dev mode and the tests. Nothing touches the Kubernetes API.
		 */
		SINGLE,

		/**
		 * Instances compete for a Kubernetes {@code Lease}; losing it ends the process.
		 */
		KUBERNETES_LEASE
	}
}