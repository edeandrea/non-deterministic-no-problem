package org.parasol.intake.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import org.parasol.claim.model.Claim;
import org.parasol.intake.model.ClaimIntakeState;
import org.parasol.intake.observability.IntakeActivity.ClaimCompleted;
import org.parasol.intake.observability.IntakeActivity.ClaimStatusChanged;
import org.parasol.intake.observability.IntakeActivity.EmailAccepted;
import org.parasol.intake.observability.IntakeActivity.EmailClassified;
import org.parasol.intake.observability.IntakeActivity.EmailHandled;
import org.parasol.intake.observability.IntakeActivity.EmailMatched;
import org.parasol.intake.observability.IntakeActivity.InboxDepthMeasured;
import org.parasol.intake.observability.IntakeActivity.IntakeFailed;
import org.parasol.intake.observability.IntakeActivity.MailClientCompleted;
import org.parasol.intake.observability.IntakeActivity.MailboxLockAcquired;
import org.parasol.intake.observability.IntakeActivity.NewClaimExtracted;
import org.parasol.intake.observability.IntakeActivity.ReplySent;
import org.parasol.intake.observability.IntakeActivity.ReviewDecisionApplied;
import org.parasol.intake.observability.IntakeActivity.ReviewReclaimed;
import org.parasol.intake.observability.IntakeActivity.WatcherConnectionChanged;
import org.parasol.intake.observability.IntakeActivity.WatcherReconnecting;

import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.Startup;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

/**
 * The email intake's metrics: what came in and what became of it. It observes the {@link IntakeActivity} the intake fires, so
 * the intake's code never touches a meter.
 * <p>
 *   Every tag value is bounded and comes from the code itself — an enum constant, a reply template's name, a mail operation
 *   or an exception's class name — so no claim number, {@code Message-ID}, address or conversation id ever becomes a tag.
 *   All of them but one are a small fixed set; {@code exception} is bounded only by the classpath, which is the point of it,
 *   so group a dashboard by the enumerated {@code stage} rather than by {@code exception}.
 * </p>
 */
@Startup
@ApplicationScoped
class IntakeMetrics {
	// Explicit bucket boundaries, so a dashboard's p50/p95 is meaningful: without them the OTel bridge exports the SDK's default
	// millisecond boundaries (0 to 10 s) and a review wait of hours all lands in +Inf. Not publishPercentileHistogram(), which is ~70 buckets per series.
	static final Duration[] EMAIL_LATENCY_BUCKETS = seconds(1, 5, 15, 30, 60, 120, 300, 600, 1800);
	static final Duration[] COMPLETION_BUCKETS = seconds(60, 300, 900, 3600, 4 * 3600, 86400, 3 * 86400, 7 * 86400);
	static final Duration[] MAIL_CLIENT_BUCKETS = millis(5, 10, 25, 50, 100, 250, 500, 1000, 5000);
	static final Duration[] LOCK_WAIT_BUCKETS = millis(1, 5, 10, 50, 100, 500, 1000, 5000);
	static final double[] EMAIL_SIZE_BUCKETS = { 4_096, 16_384, 65_536, 262_144, 1_048_576, 4_194_304, 16_777_216 };
	static final double[] COMPLETENESS_BUCKETS = { 0.2, 0.4, 0.6, 0.8, 1.0 };
	static final double[] CLAIM_EMAILS_BUCKETS = { 1, 2, 3, 5, 8 };

	private final MeterRegistry registry;
	private final AtomicInteger watcherConnected = new AtomicInteger();
	private final AtomicInteger inboxDepth = new AtomicInteger(-1);
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

		var depth = Gauge.builder("claim.intake.inbox.depth", this.inboxDepth, value -> (value.get() < 0) ? Double.NaN : value.get())
			.description("Emails waiting in the claims INBOX at the last measurement; NaN before the first")
			.strongReference(true)
			.register(registry);

		var oldest = Gauge.builder("claim.intake.review.oldest.pending", this, IntakeMetrics::oldestPendingReviewSecondsOrNaN)
			.description("Seconds the claim that has waited longest in Pending Review has been waiting; 0 if none is")
			.baseUnit("seconds")
			.strongReference(true)
			.register(registry);

		this.gauges = List.of(connected, pending, depth, oldest);
	}

	void onReviewReclaimed(@Observes ReviewReclaimed reclaimed) {
		Counter.builder("claim.intake.reviews.reclaimed")
			.description("Reviews whose run was gone and that the restarted application gave to a new run")
			.register(this.registry)
			.increment();
	}

	void onEmailHandled(@Observes EmailHandled handled) {
		this.registry.counter("claim.intake.emails", Tags.of("outcome", handled.outcome().tag())).increment();
		handled.latency()
		       .ifPresent(latency -> Timer.builder("claim.intake.email.latency")
		                                  .description("From the mailbox receiving an email to it leaving the processing folder, a review wait excluded")
		                                  .tag("outcome", handled.outcome().tag())
		                                  .serviceLevelObjectives(EMAIL_LATENCY_BUCKETS)
		                                  .register(this.registry)
		                                  .record(latency.isNegative() ? Duration.ZERO : latency));
	}

	void onEmailAccepted(@Observes EmailAccepted accepted) {
		DistributionSummary.builder("claim.intake.email.size")
			.description("The size of an accepted email")
			.baseUnit("bytes")
			.serviceLevelObjectives(EMAIL_SIZE_BUCKETS)
			.register(this.registry)
			.record(accepted.sizeBytes());

		this.registry.counter("claim.intake.attachments", Tags.of("result", "kept", "reason", "none")).increment(accepted.keptImages());
		accepted.skippedReasons()
		        .forEach(reason -> this.registry.counter("claim.intake.attachments", Tags.of("result", "skipped", "reason", reason.name().toLowerCase(Locale.ROOT))).increment());
	}

	void onEmailMatched(@Observes EmailMatched matched) {
		this.registry.counter("claim.intake.matcher.result", Tags.of("result", matched.result().tag())).increment();
	}

	void onEmailClassified(@Observes EmailClassified classified) {
		this.registry.counter("claim.intake.classifications",
			Tags.of("email_type", classified.emailType().name().toLowerCase(Locale.ROOT), "matched_claim", classified.matchedClaim().tag())).increment();
	}

	void onNewClaimExtracted(@Observes(during = TransactionPhase.AFTER_SUCCESS) NewClaimExtracted extracted) {
		DistributionSummary.builder("claim.intake.extraction.completeness")
			.description("The share of the required fields the agents filled for a new claim, from 0 to 1")
			.serviceLevelObjectives(COMPLETENESS_BUCKETS)
			.register(this.registry)
			.record(extracted.completeness());

		extracted.missingItems()
		         .forEach(item -> this.registry.counter("claim.intake.missing.items", Tags.of("item", item.name().toLowerCase(Locale.ROOT))).increment());
	}

	void onClaimCompleted(@Observes(during = TransactionPhase.AFTER_SUCCESS) ClaimCompleted completed) {
		DistributionSummary.builder("claim.intake.claim.emails")
			.description("The inbound emails in a claim's correspondence when a reviewer marked it ready")
			.serviceLevelObjectives(CLAIM_EMAILS_BUCKETS)
			.register(this.registry)
			.record(completed.inboundEmails());

		Timer.builder("claim.intake.claim.time.to.complete")
			.description("From a claim's first inbound email arriving to a reviewer marking it ready")
			.serviceLevelObjectives(COMPLETION_BUCKETS)
			.register(this.registry)
			.record(completed.timeToComplete().isNegative() ? Duration.ZERO : completed.timeToComplete());
	}

	void onMailClientCompleted(@Observes MailClientCompleted completed) {
		Timer.builder("claim.intake.mail.client.duration")
			.description("How long a call to the mail server took")
			.tags("protocol", completed.protocol().name().toLowerCase(Locale.ROOT), "operation", completed.operation(), "outcome", completed.failed() ? "error" : "success")
			.serviceLevelObjectives(MAIL_CLIENT_BUCKETS)
			.register(this.registry)
			.record(completed.duration());
	}

	void onMailboxLockAcquired(@Observes MailboxLockAcquired acquired) {
		Timer.builder("claim.intake.mailbox.lock.wait")
			.description("How long a call to the claims mailbox waited for its lock")
			.tag("mode", acquired.mode().tag())
			.serviceLevelObjectives(LOCK_WAIT_BUCKETS)
			.register(this.registry)
			.record(acquired.waited());
	}

	void onInboxDepthMeasured(@Observes InboxDepthMeasured measured) {
		this.inboxDepth.set(measured.messages());
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
		this.registry.counter("claim.intake.review.overrides", Tags.of("agreement", applied.agreement().tag())).increment();
		applied.waited()
		       .ifPresent(waited -> Timer.builder("claim.intake.review.wait")
		                                 .description("How long a claim waited in Pending Review for a decision")
		                                 .serviceLevelObjectives(COMPLETION_BUCKETS)
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

	private double oldestPendingReviewSecondsOrNaN() {
		if (!this.stopped) {
			try {
				return oldestPendingReviewSeconds();
			}
			catch (RuntimeException e) {
				Log.debugf(e, "Couldn't find the oldest pending review");
			}
		}

		return Double.NaN;
	}

	// Non-private, so the transaction interceptor applies. The same claims as pendingReviews counts
	@Transactional(TxType.REQUIRES_NEW)
	double oldestPendingReviewSeconds() {
		return ClaimIntakeState.oldestPendingReviewOpenedAt()
			.map(openedAt -> Math.max(0, Duration.between(openedAt, Instant.now()).toMillis() / 1000.0))
			.orElse(0.0);
	}

	// Non-private, so the transaction interceptor applies when pendingReviewsOrNaN calls it. A scrape isn't in a transaction
	@Transactional(TxType.REQUIRES_NEW)
	double pendingReviews() {
		return Claim.countPendingReview();
	}

	private static Duration[] seconds(long... values) {
		return LongStream.of(values).mapToObj(Duration::ofSeconds).toArray(Duration[]::new);
	}

	private static Duration[] millis(long... values) {
		return LongStream.of(values).mapToObj(Duration::ofMillis).toArray(Duration[]::new);
	}
}