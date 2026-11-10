package org.parasol.intake.dispatch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import jakarta.enterprise.inject.Instance;

import org.junit.jupiter.api.Test;
import org.parasol.intake.config.IntakeConfig;
import org.parasol.intake.failure.IntakeFailureHandler;
import org.parasol.intake.mailbox.ClaimsMailbox;
import org.parasol.intake.mailbox.InboundEmail;
import org.parasol.intake.mailbox.MailFolder;
import org.parasol.intake.observability.FailureOutcome;
import org.parasol.intake.observability.IntakeActivities;
import org.parasol.intake.workflow.ClaimIntakeFlow;

import ai.scoring.conversation.ConversationContext;
import io.serverlessworkflow.impl.WorkflowInstance;

/**
 * A run that throws on its way up must not wedge its sender's queue. The dispatcher registers the run as the sender's
 * working holder before it starts it, so the catch has to release the id it actually installed: releasing the
 * {@code STARTING} sentinel instead is a no-op ({@link SenderState#release}), and that sender's next email would then
 * never start. A plain Mockito test, because the throw has to come from {@code WorkflowInstance.start()}.
 */
class ClaimIntakeDispatcherStartFailureTests {
	private static final String SENDER = "jane.doe@example.com";
	private static final String RUN_ID = "run-that-fails-to-start";

	@Test
	void aRunThatThrowsWhileStartingReleasesItsSenderSoTheNextEmailStarts() {
		var flow = mock(ClaimIntakeFlow.class);
		var failing = mock(WorkflowInstance.class);
		var second = mock(WorkflowInstance.class);

		when(failing.id()).thenReturn(RUN_ID);
		when(second.id()).thenReturn("the-next-run");
		doAnswer(_ -> {
			throw new IllegalStateException("the run could not be started");
		}).when(failing).start();
		when(flow.instance(any())).thenReturn(failing, second);

		var failureHandler = mock(IntakeFailureHandler.class);
		var dispatcher = dispatcher(flow, failureHandler);

		dispatcher.submit(email("<first@example.com>"));
		dispatcher.submit(email("<second@example.com>"));

		verify(failureHandler).handle(anyString(), any(IllegalStateException.class), eq(FailureOutcome.FATAL));
		// The second email started a run of its own: the failed one released the sender rather than holding it forever
		verify(flow, times(2)).instance(any());
		verify(second).start();
	}

	@SuppressWarnings("unchecked")
	private static ClaimIntakeDispatcher dispatcher(ClaimIntakeFlow flow, IntakeFailureHandler failureHandler) {
		var config = mock(IntakeConfig.class);
		var mailbox = mock(ClaimsMailbox.class);
		var recorder = mock(CorrespondenceRecorder.class);
		var matcher = mock(ClaimMatcher.class);
		var activities = mock(IntakeActivities.class);
		var flowInstance = mock(Instance.class);
		var executor = mock(ExecutorService.class);

		when(config.address()).thenReturn("claims@parasol.com");
		when(mailbox.find(any(MailFolder.class), anyString())).thenAnswer(call -> Optional.of(email(call.getArgument(1))));
		when(recorder.recordIfNew(any(), anyString())).thenReturn(true);
		when(matcher.match(any())).thenReturn(new ClaimMatcher.ClaimMatch(null, false, "conversation-1"));
		when(flowInstance.get()).thenReturn(flow);
		// Inline, so the test sees the dispatch without waiting
		doAnswer(call -> {
			call.getArgument(0, Runnable.class).run();

			return null;
		}).when(executor).execute(any(Runnable.class));

		return new ClaimIntakeDispatcher(config, mailbox, recorder, matcher, failureHandler, new ConversationContext(), activities, flowInstance, executor, () -> true);
	}

	private static InboundEmail email(String messageId) {
		return new InboundEmail(
			Optional.of(messageId), List.of(), Optional.of(SENDER), Optional.empty(), "Car accident", Instant.EPOCH, Instant.EPOCH, 4, "Body", "Body", List.of(), List.of(), false);
	}
}
