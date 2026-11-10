package org.parasol.intake.dispatch;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;

import org.parasol.intake.config.IntakeConfig;
import org.parasol.intake.leadership.IntakeLeadership;
import org.parasol.intake.failure.IntakeFailureHandler;
import org.parasol.intake.mailbox.ClaimsMailbox;
import org.parasol.intake.mailbox.EmailReceived;
import org.parasol.intake.mailbox.InboundEmail;
import org.parasol.intake.mailbox.MailLoopGuard;
import org.parasol.intake.mailbox.MailFolder;
import org.parasol.intake.mailbox.SkippedAttachment;
import org.parasol.intake.observability.IntakeActivities;
import org.parasol.intake.observability.EmailOutcome;
import org.parasol.intake.observability.FailureOutcome;
import org.parasol.intake.observability.IntakeActivity.EmailAccepted;
import org.parasol.intake.observability.IntakeActivity.EmailHandled;
import org.parasol.intake.workflow.ClaimIntakeFlow;
import org.parasol.intake.workflow.EmailRequeued;
import org.parasol.intake.workflow.IntakeRun;
import org.parasol.intake.workflow.review.ReviewReclaimer;

import ai.scoring.conversation.ConversationContext;
import io.quarkus.logging.Log;
import io.quarkus.virtual.threads.VirtualThreads;
import io.serverlessworkflow.impl.WorkflowStatus;
import io.serverlessworkflow.impl.lifecycle.TaskStartedEvent;
import io.serverlessworkflow.impl.lifecycle.WorkflowExecutionListener;
import io.serverlessworkflow.impl.lifecycle.WorkflowStatusEvent;
import io.smallrye.mutiny.Uni;

/**
 * Starts a {@link ClaimIntakeFlow} run for each email the watcher has moved to the processing folder, and keeps each
 * sender's emails in order. It never waits for a run.
 * <p>
 *   {@link #submit} does the checks that need no claim (auto-replies and self-sent mail, a missing or repeated
 *   {@code Message-ID}) and queues the rest. When an email's turn comes, on a virtual thread, it's re-read, matched to a
 *   claim (so it sees what its predecessor created or changed), and the run is started inside the conversation context.
 * </p>
 * <p>
 *   A sender has at most one run that's working. The next email starts when that run waits for review or ends. A review run
 *   that wakes for its decision is working again until it ends, so an email that was handed back because its claim was
 *   being decided waits for the decision steps. Each sender's state is an immutable {@link SenderState}, changed only by
 *   {@code ConcurrentHashMap.compute}.
 * </p>
 */
@ApplicationScoped
public class ClaimIntakeDispatcher implements WorkflowExecutionListener {
	private static final Set<WorkflowStatus> RELEASES = EnumSet.of(WorkflowStatus.WAITING, WorkflowStatus.COMPLETED, WorkflowStatus.CANCELLED, WorkflowStatus.FAULTED);
	private static final Set<WorkflowStatus> ENDED = EnumSet.of(WorkflowStatus.COMPLETED, WorkflowStatus.CANCELLED, WorkflowStatus.FAULTED);
	private static final String DECISION_TASK = ClaimIntakeFlow.ROUTE_DECISION;
	private static final Duration FIRST_RETRY_DELAY = Duration.ofMillis(250);
	private static final int MAX_REQUEUES = 6;

	private final IntakeConfig config;
	private final ClaimsMailbox mailbox;
	private final CorrespondenceRecorder recorder;
	private final ClaimMatcher matcher;
	private final IntakeFailureHandler failureHandler;
	private final ConversationContext conversationContext;
	private final IntakeActivities activities;
	private final Instance<ClaimIntakeFlow> flow;
	private final ExecutorService executor;
	private final IntakeLeadership leadership;
	private final ProcessingRecovery recovery;
	private final ReviewReclaimer reclaimer;

	private final Map<String, SenderState> senders = new ConcurrentHashMap<>();
	private final Map<String, String> senderByInstance = new ConcurrentHashMap<>();
	private final Map<String, Integer> requeues = new ConcurrentHashMap<>();
	private final Map<String, ProcessingRecovery.Assessment> resumptions = new ConcurrentHashMap<>();

	ClaimIntakeDispatcher(
		IntakeConfig config,
		ClaimsMailbox mailbox,
		CorrespondenceRecorder recorder,
		ClaimMatcher matcher,
		IntakeFailureHandler failureHandler,
		ConversationContext conversationContext,
		IntakeActivities activities,
		Instance<ClaimIntakeFlow> flow,
		@VirtualThreads ExecutorService executor,
		IntakeLeadership leadership,
		ProcessingRecovery recovery,
		ReviewReclaimer reclaimer) {

		this.config = config;
		this.mailbox = mailbox;
		this.recorder = recorder;
		this.matcher = matcher;
		this.failureHandler = failureHandler;
		this.conversationContext = conversationContext;
		this.activities = activities;
		this.flow = flow;
		this.executor = executor;
		this.leadership = leadership;
		this.recovery = recovery;
		this.reclaimer = reclaimer;
	}

	/**
	 * Takes the email the watcher hands over. The event is synchronous, so this runs on the watcher's thread and each
	 * sender's emails are queued in the order they arrived.
	 *
	 * @param received The event
	 */
	public void onEmailReceived(@Observes EmailReceived received) {
		if (received.isRecovery()) {
			recover(received.email());
		}
		else {
			submit(received.email());
		}
	}

	/**
	 * Takes an email a previous leader left in the processing folder, and does what its progress calls for (see {@link ProcessingRecovery}):
	 * <ul>
	 *   <li>never accepted: the normal path, {@link #submit}</li>
	 *   <li>accepted, nothing applied: run again from the start, without the duplicate check</li>
	 *   <li>applied, no reply recorded: a run that resumes at the reply</li>
	 *   <li>applied and answered: filed, counted once</li>
	 * </ul>
	 * The ones that run are queued in the order they were found, which is ahead of anything the INBOX brings.
	 *
	 * @param email The email
	 */
	public void recover(InboundEmail email) {
		email.messageId()
			.filter(_ -> email.fromAddress().isPresent() && !MailLoopGuard.shouldIgnore(email, this.config.address()))
			.ifPresentOrElse(
				id -> recoverIdentified(email, id),
				() -> submit(email)
			);
	}

	private void recoverIdentified(InboundEmail email, String messageId) {
		var assessment = this.recovery.assess(messageId);

		switch (assessment.stage()) {
			case NEW -> submit(email);
			case NOT_APPLIED -> enqueueRecovered(email, messageId, null);
			case APPLIED_UNANSWERED -> enqueueRecovered(email, messageId, assessment);
			case ANSWERED -> this.executor.execute(() -> fileAnswered(email, assessment));
		}
	}

	private void enqueueRecovered(InboundEmail email, String messageId, ProcessingRecovery.Assessment resumption) {
		Log.infof("Recovering %s, left in the processing folder%s", messageId, (resumption == null) ? "" : ": its reply is the only thing left");
		Optional.ofNullable(resumption).ifPresent(found -> this.resumptions.put(messageId, found));
		transition(sender(email.fromAddress().orElseThrow()), state -> state.enqueue(messageId));
	}

	// Applied and answered, so only filing was left. A claim that now waits for review lost its run too, and nothing else will hand it a new one
	// while this email was in the processing folder
	private void fileAnswered(InboundEmail email, ProcessingRecovery.Assessment assessment) {
		try {
			Log.infof("Filing %s: it was already answered", email.messageId().orElse(""));
			this.failureHandler.fileWithoutRun(email);
			this.activities.publish(new EmailHandled(outcomeOf(assessment), Optional.of(latencyOf(email))));
			this.reclaimer.reclaim();
		}
		catch (RuntimeException e) {
			Log.errorf(e, "Couldn't file the answered email %s", email.messageId().orElse(""));
		}
	}

	private static EmailOutcome outcomeOf(ProcessingRecovery.Assessment assessment) {
		return assessment.createdClaim() ? EmailOutcome.NEW_CLAIM : (assessment.mergedIntoClaim() ? EmailOutcome.CLAIM_UPDATE : EmailOutcome.STATUS_QUESTION);
	}

	/**
	 * Accepts an email the watcher has already moved to the processing folder. Returns at once; the run isn't waited for.
	 * <ul>
	 *   <li>an auto-reply or mail from the claims address: filed, no run, no reply</li>
	 *   <li>no {@code Message-ID}: the failure path, no run</li>
	 *   <li>a {@code Message-ID} already recorded: filed, no run, no reply</li>
	 *   <li>otherwise: recorded, and started in order with the sender's other emails</li>
	 * </ul>
	 *
	 * @param email The email
	 */
	public void submit(InboundEmail email) {
		if (!this.leadership.isLeader()) {
			// Only the leader watches, so this is a regression, not a normal case. The runs this starts would compete with the leader's
			Log.warnf("An email was submitted to the intake on an application instance that isn't its leader");
		}

		if (MailLoopGuard.shouldIgnore(email, this.config.address())) {
			this.activities.publish(new EmailHandled(EmailOutcome.SKIPPED_AUTO_REPLY, Optional.of(latencyOf(email))));
			this.failureHandler.fileWithoutRun(email);
		}
		else {
			email.messageId()
			     .ifPresentOrElse(id -> submitIdentified(email, id), () -> this.failureHandler.handleWithoutMessageId(email));
		}
	}

	private void submitIdentified(InboundEmail email, String messageId) {
		email.fromAddress()
		     .ifPresentOrElse(address -> {
					 if (this.recorder.recordIfNew(email, messageId)) {
						 this.activities.publish(new EmailAccepted(
							 email.sizeBytes(),
							 email.images().size(),
							 email.skippedAttachments().stream().map(SkippedAttachment::reason).toList()));
						 transition(sender(address), state -> state.enqueue(messageId));
					 }
					 else {
						 Log.debugf("Ignoring %s: it was already accepted", messageId);
						 this.activities.publish(new EmailHandled(EmailOutcome.DUPLICATE, Optional.of(latencyOf(email))));
						 this.failureHandler.fileWithoutRun(email);
					 }
					 },
			     () -> this.failureHandler.handle(messageId, new IllegalArgumentException("The email has no sender"), FailureOutcome.FATAL));
	}

	/**
	 * Applies a transition to a sender's state atomically, then starts the email it took off the queue, if any. The start
	 * happens outside {@code compute}, which must stay free of side effects.
	 */
	private void transition(String sender, UnaryOperator<SenderState> change) {
		var after = this.senders.compute(sender, (_, current) -> {
			var next = change.apply(Optional.ofNullable(current).orElse(SenderState.IDLE));

			return next.isIdle() ? null : next;
		});

		Optional.ofNullable(after)
			.flatMap(SenderState::starting)
			.ifPresent(messageId -> dispatch(sender, messageId));
	}

	// An email handed back by a run is delayed (250 ms, doubling), so the claim's decision can finish, up to MAX_REQUEUES times
	private void dispatch(String sender, String messageId) {
		var attempts = this.requeues.getOrDefault(messageId, 0);

		if (attempts > MAX_REQUEUES) {
			this.requeues.remove(messageId);
			this.executor.execute(() -> this.failureHandler.handle(messageId, new IllegalStateException("The claim stayed under a reviewer's decision"), FailureOutcome.FATAL));
			transition(sender, state -> state.release(SenderState.STARTING));
		}
		else if (attempts == 0) {
			this.executor.execute(() -> startRun(sender, messageId));
		}
		else {
			Uni.createFrom().voidItem()
				.onItem().delayIt().by(FIRST_RETRY_DELAY.multipliedBy(1L << (attempts - 1)))
				.emitOn(this.executor)
				.subscribe().with(ignored -> startRun(sender, messageId), failure -> Log.errorf(failure, "Couldn't restart %s", messageId));
		}
	}

	private void startRun(String sender, String messageId) {
		// What holds the sender's slot right now: STARTING until the run exists, then the run's id. A failure has to release
		// the holder the state actually has, or SenderState.release no-ops and the sender's queue never moves again
		var holder = new AtomicReference<>(SenderState.STARTING);

		try {
			var email = this.mailbox.find(MailFolder.PROCESSING, messageId)
				.orElseThrow(() -> new ProcessingEmailMissingException(messageId));
			var resumption = this.resumptions.remove(messageId);
			var run = Optional.ofNullable(resumption)
				.filter(found -> found.claimId() != null)
				.map(found -> IntakeRun.resumingReply(messageId, Optional.ofNullable(found.conversationId()).orElseGet(() -> UUID.randomUUID().toString()), email.fromAddress().orElse(sender), found.claimId(), email.receivedDate()))
				.orElseGet(() -> runFor(email, messageId, sender));

			this.conversationContext.runIn(run.conversationId(), () -> {
				var instance = this.flow.get().instance(run);

				if (run.resumesReply()) {
					this.recovery.adoptReview(run.claimId(), instance.id());
				}

				// Registered as working before it starts, so the status listener always finds it
				this.senderByInstance.put(instance.id(), sender);
				transition(sender, state -> state.working(instance.id()));
				holder.set(instance.id());
				instance.start();
			});
		}
		catch (RuntimeException e) {
			// A run that threw on the way up never reaches a WorkflowStatus, so the status listener will never release it.
			// If it threw from start(), the instance also stays in Flow's in-memory activeInstances map — cancel() is a
			// no-op on a PENDING instance and removeInstance isn't public (open-workflow-specification/sdk-java#1816).
			// Harmless, since its id was never persisted and nothing looks it up, but it is a leaked map entry.
			this.senderByInstance.remove(holder.get());
			transition(sender, state -> state.release(holder.get()));
			this.failureHandler.handle(messageId, e, FailureOutcome.FATAL);
		}
	}

	private IntakeRun runFor(InboundEmail email, String messageId, String sender) {
		var match = this.matcher.match(email);

		return IntakeRun.forEmail(messageId, match.conversationId(), email.fromAddress().orElse(sender), match.claimId(), match.senderMatches(), email.receivedDate());
	}

	/**
	 * Takes an email a run handed back (its claim is being decided) to the front of its sender's queue.
	 *
	 * @param emailRequeued The email
	 */
	void onEmailRequeued(@Observes EmailRequeued emailRequeued) {
		this.requeues.merge(emailRequeued.messageId(), 1, Integer::sum);
		transition(sender(emailRequeued.sender()), state -> state.requeueFirst(emailRequeued.messageId()));
	}

	@Override
	public void onWorkflowStatusChanged(WorkflowStatusEvent event) {
		if (isIntakeRun(event.workflowContext().definition().id().name())) {
			var instanceId = event.workflowContext().instanceData().id();

			if (RELEASES.contains(event.status())) {
				releaseRun(instanceId, event.status());
			}
			else if ((event.status() == WorkflowStatus.RUNNING) && (event.previousStatus() == WorkflowStatus.WAITING)) {
				markWorking(instanceId);
			}
		}
	}

	// The wake-up of a review run: nothing sets RUNNING when the event arrives, so the status change that precedes the
	// next task is the earliest signal; the first decision task is a backstop for it
	@Override
	public void onTaskStarted(TaskStartedEvent event) {
		if (isIntakeRun(event.workflowContext().definition().id().name()) && DECISION_TASK.equals(event.taskContext().taskName())) {
			markWorking(event.workflowContext().instanceData().id());
		}
	}

	private static boolean isIntakeRun(String workflowName) {
		return ClaimIntakeFlow.NAME.equals(workflowName);
	}

	private void markWorking(String instanceId) {
		Optional.ofNullable(this.senderByInstance.get(instanceId))
			.ifPresent(sender -> transition(sender, state -> state.markWorking(instanceId)));
	}

	private void releaseRun(String instanceId, WorkflowStatus status) {
		var sender = ENDED.contains(status) ? this.senderByInstance.remove(instanceId) : this.senderByInstance.get(instanceId);

		Optional.ofNullable(sender)
			.ifPresent(found -> transition(found, state -> state.release(instanceId)));
	}

	private static Duration latencyOf(InboundEmail email) {
		return Duration.between(email.receivedDate(), Instant.now());
	}

	private static String sender(String address) {
		return address.toLowerCase(Locale.ROOT);
	}
}
