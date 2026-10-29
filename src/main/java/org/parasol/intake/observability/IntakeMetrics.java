package org.parasol.intake.observability;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import org.parasol.claim.model.Claim;
import org.parasol.intake.observability.IntakeActivity.ClaimStatusChanged;
import org.parasol.intake.observability.IntakeActivity.EmailHandled;
import org.parasol.intake.observability.IntakeActivity.IntakeFailed;
import org.parasol.intake.observability.IntakeActivity.ReplySent;
import org.parasol.intake.observability.IntakeActivity.ReviewDecisionApplied;
import org.parasol.intake.observability.IntakeActivity.WatcherConnectionChanged;
import org.parasol.intake.observability.IntakeActivity.WatcherReconnecting;

import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.Startup;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

/**
 * The email intake's metrics: what came in and what became of it. It observes the {@link IntakeActivity} the intake fires, so
 * the intake's code never touches a meter. Every tag has a small, fixed set of values, so no claim number, {@code Message-ID},
 * address or conversation id ever becomes a tag.
 */
@Startup
@ApplicationScoped
class IntakeMetrics {
	private final MeterRegistry registry;
	private final AtomicInteger watcherConnected = new AtomicInteger();
	private final Counter watcherReconnects;
	private List<Gauge> gauges = List.of();
	private volatile boolean stopped;

	IntakeMetrics(MeterRegistry registry) {
		this.registry = registry;
		this.watcherReconnects = Counter.builder("claim.intake.watcher.reconnects")
			.description("How often the inbox watcher had to connect to the claims mailbox again")
			.register(registry);

		var connected = Gauge.builder("claim.intake.watcher.connected", this.watcherConnected, AtomicInteger::get)
			.description("1 while the inbox watcher's IDLE connection is up, 0 otherwise")
			.strongReference(true)
			.register(registry);

		var pending = Gauge.builder("claim.intake.reviews.pending", this, IntakeMetrics::pendingReviewsOrNaN)
			.description("Claims waiting for a reviewer")
			.strongReference(true)
			.register(registry);

		this.gauges = List.of(connected, pending);
	}

	void onEmailHandled(@Observes EmailHandled handled) {
		this.registry.counter("claim.intake.emails", Tags.of("outcome", handled.outcome().tag())).increment();
	}

	void onReplySent(@Observes ReplySent sent) {
		this.registry.counter("claim.intake.replies", Tags.of("template", sent.template())).increment();
	}

	void onFailed(@Observes IntakeFailed failed) {
		this.registry.counter("claim.intake.failures", Tags.of("stage", failed.stage().tag(), "exception", failed.exception(), "outcome", failed.outcome().tag())).increment();
	}

	// Only what committed is counted: a rolled-back change leaves the claim as it was
	void onClaimStatusChanged(@Observes(during = TransactionPhase.AFTER_SUCCESS) ClaimStatusChanged changed) {
		this.registry.counter("claim.intake.claim.status.transitions", Tags.of("status", changed.status().label())).increment();
	}

	void onReviewDecisionApplied(@Observes(during = TransactionPhase.AFTER_SUCCESS) ReviewDecisionApplied applied) {
		this.registry.counter("claim.intake.review.decisions", Tags.of("decision", applied.decision().name().toLowerCase())).increment();
		applied.waited()
		       .ifPresent(waited -> Timer.builder("claim.intake.review.wait")
		                                 .description("How long a claim waited in Pending Review for a decision")
		                                 .register(this.registry)
		                                 .record(waited));
	}

	void onWatcherConnectionChanged(@Observes WatcherConnectionChanged changed) {
		this.watcherConnected.set(changed.connected() ? 1 : 0);
	}

	void onWatcherReconnecting(@Observes WatcherReconnecting reconnecting) {
		this.watcherReconnects.increment();
	}

	// OpenTelemetry flushes its meters while the application is being torn down, after Hibernate has gone, so a gauge read then must not query.
	// The gauges are removed too: the global registry outlives the application in a test JVM, and a restarted application's registry would
	// otherwise pick up this instance's gauges and read NaN from them
	void onShutdown(@Observes ShutdownEvent event) {
		this.stopped = true;
		this.gauges.forEach(this.registry::remove);
	}

	private double pendingReviewsOrNaN() {
		if (!this.stopped) {
			try {
				return pendingReviews();
			}
			catch (RuntimeException e) {
				Log.debugf(e, "Couldn't count the pending reviews");
			}
		}

		return Double.NaN;
	}

	// Non-private, so the transaction interceptor applies when pendingReviewsOrNaN calls it. A scrape isn't in a transaction
	@Transactional(TxType.REQUIRES_NEW)
	double pendingReviews() {
		return Claim.countPendingReview();
	}
}
