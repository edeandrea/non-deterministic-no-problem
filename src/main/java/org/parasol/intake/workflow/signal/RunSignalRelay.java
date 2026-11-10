package org.parasol.intake.workflow.signal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import org.parasol.claim.model.Claim;
import org.parasol.intake.model.ClaimIntakeState;
import org.parasol.intake.model.IntakeRunSignal;
import org.parasol.intake.observability.IntakeActivities;
import org.parasol.intake.observability.IntakeActivity.RunSignalDelivered;
import org.parasol.intake.review.ReviewDecided;
import org.parasol.intake.review.ReviewDecisionPublisher;
import org.parasol.intake.workflow.ClaimIntakeFlow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import io.serverlessworkflow.impl.WorkflowStatus;

/**
 * Gets signals to the intake runs they're for. A run lives in one JVM and Flow's events don't cross application instances, so a reviewer's decision
 * isn't published by the application instance that took the request. It's written to {@code intake_run_signal}, in the transaction that claims the
 * review ({@link #enqueue}), and this relay hands it to the run from the application instance that hosts it.
 * <p>
 *   Every application instance runs one. A signal is tried once right after it's written ({@link #deliver()}, so a single application instance keeps its latency) and
 *   then on every poll, and is only ever published to a run that's hosted <em>here</em> and waiting: a signal for another application instance's run
 *   stays pending for that application instance. A run that hasn't reached its wait yet misses the event, so the signal is republished until the run has
 *   taken it, which closes the gap between a run reading as waiting and its {@code listen} registering a consumer.
 * </p>
 * <p>
 *   A signal is <em>taken</em> once the claim's {@code decidingRunId} no longer names the run: the decision steps clear it when they apply
 *   the decision, and put the claim back in review (also clearing it) when they fault. Republishing a decision the run already consumed is
 *   harmless, since the run id and the claim's state are checked again when it's delivered. Taken signals are deleted.
 * </p>
 */
@ApplicationScoped
public class RunSignalRelay {
	private final ClaimIntakeFlow flow;
	private final ReviewDecisionPublisher publisher;
	private final ObjectMapper objectMapper;
	private final IntakeActivities activities;
	private final ReentrantLock delivering = new ReentrantLock();

	RunSignalRelay(ClaimIntakeFlow flow, ReviewDecisionPublisher publisher, ObjectMapper objectMapper, IntakeActivities activities) {
		this.flow = flow;
		this.publisher = publisher;
		this.objectMapper = objectMapper;
		this.activities = activities;
	}

	/**
	 * Writes the signal for a reviewer's decision, in the caller's transaction, so it commits with the change that claimed the review or
	 * not at all. Call {@link #deliver()} once the transaction has committed.
	 *
	 * @param claim the reviewed claim
	 * @param decided the decision, with the run it's for
	 */
	public void enqueue(Claim claim, ReviewDecided decided) {
		IntakeRunSignal.record(claim, decided.runId(), IntakeRunSignal.Kind.REVIEW_DECIDED, toJson(decided));
	}

	/**
	 * Hands every pending signal whose run is hosted in this JVM and waiting to that run, and deletes the ones a run has taken. A failure
	 * is logged and leaves the signal pending: the next poll tries again.
	 */
	public void deliver() {
		// A publish waits for the run to take the event, so don't stack a second pass behind a slow one: the next poll comes soon enough
		if (this.delivering.tryLock()) {
			try {
				claimDue().forEach(this::publish);
			}
			catch (RuntimeException e) {
				Log.warnf(e, "Couldn't deliver the pending signals to the intake runs");
			}
			finally {
				this.delivering.unlock();
			}
		}
	}

	@Scheduled(every = "${parasol.intake.signals.poll-interval}", concurrentExecution = ConcurrentExecution.SKIP)
	void poll() {
		deliver();
	}

	/**
	 * What to publish to a run: the decision a reviewer made for it.
	 *
	 * @param decided the decision, with the claim and run it's for
	 */
	record Due(ReviewDecided decided) {
	}

	/**
	 * Settles the pending signals in one transaction and returns the ones to publish. Every pending signal falls into one of three groups:
	 * <ul>
	 *   <li><b>Taken:</b> the run has already consumed it (see {@link #isTaken}). It's deleted, and its delivery is published as an activity
	 *     (right after its delete).</li>
	 *   <li><b>Due:</b> not taken, and its run is hosted in this JVM and waiting, so it can be published now. Its {@code attempts} is incremented, and
	 *     it's returned. A signal whose payload can't be read is skipped (and logged), and stays pending.</li>
	 *   <li><b>Neither:</b> not taken, and its run isn't waiting here, usually because another application instance hosts it. It's left alone for
	 *     that instance's relay.</li>
	 * </ul>
	 * The signals are published by the caller, outside this transaction: a publish waits for the run to take the event, which can take a while.
	 *
	 * @return the signals to publish, oldest first
	 */
	// Its own transaction, and not around the publish. Non-private and non-final, so the transaction interceptor applies to the call from this class
	@Transactional(TxType.REQUIRES_NEW)
	List<Due> claimDue() {
		// true: taken by their run; false: still pending
		var byTaken = IntakeRunSignal.listPending().stream()
			.collect(Collectors.partitioningBy(RunSignalRelay::isTaken));
		var taken = byTaken.get(true);
		var waitingHere = byTaken.get(false).stream()
			.filter(signal -> isWaitingHere(signal.runId))
			.toList();

		taken.forEach(signal -> {
			signal.delete();
			this.activities.publish(new RunSignalDelivered(signal.kind, Duration.between(signal.createdAt, Instant.now())));
		});

		waitingHere.forEach(signal -> signal.attempts++);

		return waitingHere.stream()
			.flatMap(signal -> fromJson(signal).stream())
			.map(Due::new)
			.toList();
	}

	/**
	 * Whether the run has consumed the signal: the claim's {@code decidingRunId} no longer names the run. The decision steps clear it when they
	 * apply the decision, and when they fault and put the claim back in review.
	 */
	private static boolean isTaken(IntakeRunSignal signal) {
		return ClaimIntakeState.findForClaim(signal.claim.id)
			.filter(state -> signal.runId.equals(state.decidingRunId))
			.isEmpty();
	}

	// A PENDING instance is a stand-in Flow leaves behind for a run it doesn't host; only a live waiting one is a run here
	@SuppressWarnings("resource")
	private boolean isWaitingHere(String runId) {
		return this.flow.definition().activeInstance(runId)
			.filter(run -> run.status() == WorkflowStatus.WAITING)
			.isPresent();
	}

	private void publish(Due due) {
		try {
			this.publisher.publish(due.decided());
		}
		catch (RuntimeException e) {
			Log.warnf(e, "Couldn't publish the review decision for claim %d to run %s", due.decided().claimId(), due.decided().runId());
		}
	}

	private Optional<ReviewDecided> fromJson(IntakeRunSignal signal) {
		try {
			return Optional.of(this.objectMapper.readValue(signal.payload, ReviewDecided.class));
		}
		catch (JsonProcessingException e) {
			Log.errorf(e, "Signal %d for run %s can't be read", signal.id, signal.runId);

			return Optional.empty();
		}
	}

	private String toJson(ReviewDecided decided) {
		try {
			return this.objectMapper.writeValueAsString(decided);
		}
		catch (JsonProcessingException e) {
			throw new IllegalStateException("Couldn't serialize the review decision", e);
		}
	}
}
