package org.parasol.intake.dispatch;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.parasol.intake.IntakeConfig;
import org.parasol.intake.failure.IntakeFailureHandler;
import org.parasol.intake.mailbox.ClaimsMailbox;
import org.parasol.intake.mailbox.InboundEmail;
import org.parasol.intake.mailbox.MailLoopGuard;
import org.parasol.intake.mailbox.MailFolder;
import org.parasol.intake.workflow.ClaimIntakeFlow;
import org.parasol.intake.workflow.EmailRequeued;
import org.parasol.intake.workflow.IntakeRun;

import ai.scoring.conversation.ConversationContext;
import io.quarkus.logging.Log;
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
 *   {@code Message-ID}) and queues the rest. When an email's turn comes, on the managed executor, it's re-read, matched to a
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
public class ClaimIntakeStarter implements WorkflowExecutionListener {
	private static final Set<WorkflowStatus> RELEASES = EnumSet.of(WorkflowStatus.WAITING, WorkflowStatus.COMPLETED, WorkflowStatus.CANCELLED, WorkflowStatus.FAULTED);
	private static final Set<WorkflowStatus> ENDED = EnumSet.of(WorkflowStatus.COMPLETED, WorkflowStatus.CANCELLED, WorkflowStatus.FAULTED);
	private static final String DECISION_TASK = "routeDecision";
	private static final Duration FIRST_RETRY_DELAY = Duration.ofMillis(250);
	private static final int MAX_REQUEUES = 6;

	private final IntakeConfig config;
	private final ClaimsMailbox mailbox;
	private final CorrespondenceRecorder recorder;
	private final ClaimMatcher matcher;
	private final IntakeFailureHandler failureHandler;
	private final ConversationContext conversationContext;
	private final Instance<ClaimIntakeFlow> flow;
	private final ManagedExecutor executor;

	private final Map<String, SenderState> senders = new ConcurrentHashMap<>();
	private final Map<String, String> senderByInstance = new ConcurrentHashMap<>();
	private final Map<String, Integer> requeues = new ConcurrentHashMap<>();

	ClaimIntakeStarter(
		IntakeConfig config,
		ClaimsMailbox mailbox,
		CorrespondenceRecorder recorder,
		ClaimMatcher matcher,
		IntakeFailureHandler failureHandler,
		ConversationContext conversationContext,
		Instance<ClaimIntakeFlow> flow,
		ManagedExecutor executor) {

		this.config = config;
		this.mailbox = mailbox;
		this.recorder = recorder;
		this.matcher = matcher;
		this.failureHandler = failureHandler;
		this.conversationContext = conversationContext;
		this.flow = flow;
		this.executor = executor;
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
		if (MailLoopGuard.shouldIgnore(email, this.config.address())) {
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
						 transition(sender(address), state -> state.enqueue(messageId));
					 }
					 else {
						 Log.debugf("Ignoring %s: it was already accepted", messageId);
						 this.failureHandler.fileWithoutRun(email);
					 }
					 },
			     () -> this.failureHandler.handle(messageId, new IllegalArgumentException("The email has no sender")));
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
			this.executor.execute(() -> this.failureHandler.handle(messageId, new IllegalStateException("The claim stayed under a reviewer's decision")));
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
		try {
			var email = this.mailbox.find(MailFolder.PROCESSING, messageId)
				.orElseThrow(() -> new IllegalStateException("%s isn't in the processing folder".formatted(messageId)));
			var match = this.matcher.match(email);
			var run = IntakeRun.forEmail(messageId, match.conversationId(), email.fromAddress().orElse(sender), match.claimId(), match.senderMatches());

			this.conversationContext.runIn(match.conversationId(), () -> {
				var instance = this.flow.get().instance(run);

				// Registered as working before it starts, so the status listener always finds it
				this.senderByInstance.put(instance.id(), sender);
				transition(sender, state -> state.working(instance.id()));
				instance.start();
			});
		}
		catch (RuntimeException e) {
			transition(sender, state -> state.release(SenderState.STARTING));
			this.failureHandler.handle(messageId, e);
		}
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

	private static String sender(String address) {
		return address.toLowerCase(Locale.ROOT);
	}
}
