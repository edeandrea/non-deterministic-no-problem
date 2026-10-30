package org.parasol.intake.mailbox;

import jakarta.enterprise.context.ApplicationScoped;

import org.parasol.intake.config.IntakeConfig;
import org.parasol.intake.observability.IntakeActivities;
import org.parasol.intake.observability.IntakeActivity.InboxDepthMeasured;

import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;

/**
 * Measures how many emails wait in the INBOX and publishes it, so the backlog shows even when the watcher is slow or down. A gauge
 * that read IMAP itself would log in on every scrape, and could wait behind a move that holds the mailbox's write lock.
 * <p>
 *   It does nothing while the intake is off, like the watcher, and never overlaps itself: a measurement stuck on the IMAP timeout
 *   can't pile up behind itself. A failed measurement is skipped, and the gauge keeps the last value.
 * </p>
 */
@ApplicationScoped
class InboxDepthMonitor {
	private final IntakeConfig config;
	private final ClaimsMailbox mailbox;
	private final IntakeActivities activities;

	InboxDepthMonitor(IntakeConfig config, ClaimsMailbox mailbox, IntakeActivities activities) {
		this.config = config;
		this.mailbox = mailbox;
		this.activities = activities;
	}

	@Scheduled(every = "${parasol.intake.metrics.inbox-depth-interval}", concurrentExecution = ConcurrentExecution.SKIP)
	void measure() {
		if (this.config.enabled()) {
			try {
				this.activities.publish(new InboxDepthMeasured(this.mailbox.inboxDepth()));
			}
			catch (RuntimeException e) {
				Log.debugf(e, "Couldn't measure the claims INBOX");
			}
		}
	}
}
