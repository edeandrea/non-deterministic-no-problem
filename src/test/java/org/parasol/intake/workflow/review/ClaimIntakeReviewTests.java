package org.parasol.intake.workflow.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;


import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.mockito.Mockito;
import org.parasol.intake.IntakeFlowTestBase;
import org.parasol.intake.IntakeScenario;
import org.parasol.intake.IntakeScenario.Mail;
import org.parasol.intake.MissingItem;
import org.parasol.intake.mailbox.MailFolder;
import org.parasol.intake.model.EmailType;
import org.parasol.intake.review.ReviewDecided;
import org.parasol.intake.review.ReviewDecision;
import org.parasol.intake.review.ReviewOutcome;
import org.parasol.intake.review.ReviewedFields;
import org.parasol.testing.StubbedModelsTestProfile;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ClientProxy;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.serverlessworkflow.impl.WorkflowStatus;

/**
 * The review: superseding a waiting review, the race with a decision, and the decision steps.
 */
@QuarkusTest
@TestProfile(StubbedModelsTestProfile.class)
class ClaimIntakeReviewTests extends IntakeFlowTestBase {
	private static final Duration DECISION_DELAY = Duration.ofSeconds(6);

	@InjectSpy
	ReviewDecisionSteps decisionSteps;

	// TEMPORARY DIAGNOSTICS (#270): which ReviewDecisionSteps the flow reaches in CI. Remove before merging
	@BeforeEach
	void diagnoseBefore(TestInfo testInfo) {
		var proxy = Arc.container().instance(ReviewDecisionSteps.class).get();
		Log.infof("DIAG before %s: spy=%s proxyTarget=%s stubbings=%s",
			testInfo.getDisplayName(),
			System.identityHashCode(this.decisionSteps),
			System.identityHashCode(ClientProxy.unwrap(proxy)),
			Mockito.mockingDetails(this.decisionSteps).getStubbings());
	}

	@AfterEach
	void diagnoseAfter(TestInfo testInfo) {
		var proxy = Arc.container().instance(ReviewDecisionSteps.class).get();
		Log.infof("DIAG after %s: spy=%s proxyTarget=%s invocations=%s stubbings=%s",
			testInfo.getDisplayName(),
			System.identityHashCode(this.decisionSteps),
			System.identityHashCode(ClientProxy.unwrap(proxy)),
			Mockito.mockingDetails(this.decisionSteps).getInvocations(),
			Mockito.mockingDetails(this.decisionSteps).getStubbings());
	}

	@Test
	void readyMovesTheClaimInProcessWithOneThankYouAndNoModelCall() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		var run = this.scenario.awaitWaitingRun(created.claim().id);
		var requestsBefore = this.stubs.requests().size();

		this.scenario.decide(created.claim().id, ReviewOutcome.READY, Set.of());

		Awaitility.await().atMost(IntakeScenario.WAIT).until(() -> this.scenario.claim(created.claim().id).status.equals("In Process"));
		var state = this.scenario.intake(created.claim().id).orElseThrow();

		assertThat(state.reviewRunId).isNull();
		assertThat(state.decidingRunId).isNull();
		assertThat(this.scenario.awaitReplies(SENDER, 2))
			.hasSize(2)
			.last()
			.satisfies(reply -> assertThat(reply.body()).contains(created.claim().claimNumber, "In Process"));
		assertThat(this.stubs.requests()).hasSize(requestsBefore);
		Awaitility.await().atMost(IntakeScenario.WAIT).until(() -> this.scenario.instanceRows() == 0);
		assertThat(run.status()).isEqualTo(WorkflowStatus.COMPLETED);
	}

	@Test
	void aFollowUpAfterTheDecisionGetsAStatusReplyAndTheConversationNeverChanges() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		var conversationId = this.scenario.intake(created.claim().id).orElseThrow().intakeConversationId;
		this.scenario.decide(created.claim().id, ReviewOutcome.READY, Set.of());
		Awaitility.await().atMost(IntakeScenario.WAIT).until(() -> this.scenario.claim(created.claim().id).status.equals("In Process"));
		var body = this.scenario.claim(created.claim().id).body;

		var marker = marker();
		classify(marker, EmailType.CLAIM_FOLLOW_UP);
		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).inReplyTo(created.messageId()).body("Any news? " + marker)));

		assertThat(this.scenario.awaitReplies(SENDER, 3))
			.last()
			.satisfies(reply -> assertThat(reply.body()).contains(STATUS_ANSWER));
		assertThat(this.scenario.claim(created.claim().id))
			.returns("In Process", c -> c.status)
			.returns(body, c -> c.body);
		assertThat(this.scenario.intake(created.claim().id).orElseThrow().intakeConversationId).isEqualTo(conversationId);
	}

	@Test
	void aReplyAnsweringTheTickedItemsGoesBackToReview() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		this.scenario.decide(created.claim().id, ReviewOutcome.NEEDS_INFORMATION, Set.of(MissingItem.LOCATION));
		Awaitility.await().atMost(IntakeScenario.WAIT).until(() -> this.scenario.claim(created.claim().id).status.equals("Pending Information"));

		var marker = marker();
		stubFollowUp(marker, details("Car hit a post.", "2026-10-07", "Elm Street, by the school", "SINGLE_VEHICLE", null, List.of("LOCATION")));
		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).inReplyTo(created.messageId()).body("It was by the school on Elm Street. " + marker)));

		var run = this.scenario.awaitWaitingRun(created.claim().id);

		assertThat(this.scenario.claim(created.claim().id))
			.returns("Pending Review", c -> c.status)
			.returns("Elm Street, by the school", c -> c.location);
		assertThat(this.scenario.intake(created.claim().id).orElseThrow())
			.returns(run.id(), state -> state.reviewRunId)
			.satisfies(state -> assertThat(state.requestedItems).isEmpty());
		assertThat(this.scenario.awaitReplies(SENDER, 3))
			.last()
			.satisfies(reply -> assertThat(reply.body()).contains("Pending Review"));
	}

	@Test
	void needsInformationAsksForExactlyTheTickedItems() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);

		this.scenario.decide(created.claim().id, ReviewOutcome.NEEDS_INFORMATION, Set.of(MissingItem.LOCATION, MissingItem.CATEGORY));

		Awaitility.await().atMost(IntakeScenario.WAIT).until(() -> this.scenario.claim(created.claim().id).status.equals("Pending Information"));
		var state = this.scenario.intake(created.claim().id).orElseThrow();

		assertThat(state.requestedItems).containsExactlyInAnyOrder(MissingItem.LOCATION, MissingItem.CATEGORY);
		assertThat(state.reviewRunId).isNull();
		assertThat(state.decidingRunId).isNull();
		assertThat(this.scenario.awaitReplies(SENDER, 2))
			.last()
			.satisfies(reply -> assertThat(reply.body()).contains("- location", "- category").doesNotContain("incident description", "incident date"));
	}

	@Test
	void anEmptyReplyAfterNeedsInformationStaysPendingInformation() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		this.scenario.decide(created.claim().id, ReviewOutcome.NEEDS_INFORMATION, Set.of(MissingItem.LOCATION));
		Awaitility.await().atMost(IntakeScenario.WAIT).until(() -> this.scenario.claim(created.claim().id).status.equals("Pending Information"));

		// A blank reply answers nothing. The stubs from the first email still apply: they're keyed on its text, which the
		// claim's correspondence carries
		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).inReplyTo(created.messageId()).body(" ")));

		assertThat(this.scenario.claim(created.claim().id).status).isEqualTo("Pending Information");
		assertThat(this.scenario.awaitReplies(SENDER, 3))
			.last()
			.satisfies(reply -> assertThat(reply.body()).contains("location"));
	}

	@Test
	void aDecisionForAnotherRunDoesNotWakeTheRun() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		var run = this.scenario.awaitWaitingRun(created.claim().id);

		for (var attempt = 0; attempt < 3; attempt++) {
			this.scenario.publish(new ReviewDecided(created.claim().id, "some-other-run", new ReviewDecision(ReviewOutcome.READY, Set.of(), ReviewedFields.none())));
		}

		Awaitility.await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).until(() -> true);

		assertThat(run.status()).isEqualTo(WorkflowStatus.WAITING);
		assertThat(this.scenario.claim(created.claim().id).status).isEqualTo("Pending Review");
	}

	@Test
	void aReplyDuringReviewCancelsTheWaitingRunAndMergesTheReply() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		var oldRun = this.scenario.awaitWaitingRun(created.claim().id);
		var oldRunId = oldRun.id();
		var conversationId = this.scenario.intake(created.claim().id).orElseThrow().intakeConversationId;

		var marker = marker();
		stubFollowUp(marker, COMPLETE_DETAILS.replace("Elm Street", "Oak Avenue"));
		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).inReplyTo(created.messageId()).body("Actually Oak Avenue. " + marker)));

		var newRun = Awaitility.await()
			.atMost(IntakeScenario.WAIT)
			.until(() -> this.scenario.waitingRun(created.claim().id).filter(run -> !run.id().equals(oldRunId)), Optional::isPresent)
			.orElseThrow();

		assertThat(oldRun.status()).isEqualTo(WorkflowStatus.CANCELLED);
		assertThat(this.scenario.claim(created.claim().id))
			.returns("Pending Review", c -> c.status)
			.satisfies(c -> assertThat(c.body).contains("Actually Oak Avenue."));
		assertThat(this.scenario.intake(created.claim().id).orElseThrow())
			.returns(newRun.id(), state -> state.reviewRunId)
			.returns(conversationId, state -> state.intakeConversationId);
		assertThat(this.scenario.awaitReplies(SENDER, 2))
			.last()
			.satisfies(reply -> assertThat(reply.body()).containsIgnoringCase("latest information"));

		// The canceled run leaves no Flow rows, and its workflow.execute span is still exported (quarkus-flow#1058, fixed in 1.2.0)
		Awaitility.await().atMost(IntakeScenario.WAIT).until(() -> (this.scenario.instanceRows(oldRunId) == 0) && (this.scenario.taskRows(oldRunId) == 0));
		Awaitility.await().atMost(IntakeScenario.WAIT).until(() -> this.spans.ofRun(oldRunId), Optional::isPresent);

		// A later decision reaches only the new run
		this.scenario.decide(created.claim().id, ReviewOutcome.READY, Set.of());
		Awaitility.await().atMost(IntakeScenario.WAIT).until(() -> this.scenario.claim(created.claim().id).status.equals("In Process"));
		assertThat(this.scenario.awaitReplies(SENDER, 3)).hasSize(3);
		Awaitility.await().atMost(IntakeScenario.WAIT).until(() -> this.scenario.instanceRows() == 0);
	}

	@Test
	void aSupersedingReplyThatMakesTheClaimIncompleteMovesItToPendingInformation() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		var oldRun = this.scenario.awaitWaitingRun(created.claim().id);

		var marker = marker();
		stubFollowUp(marker, details("Car hit a post.", "2026-10-07", null, "SINGLE_VEHICLE", null, List.of()));
		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).inReplyTo(created.messageId()).body("Never mind the location. " + marker)));

		assertThat(this.scenario.claim(created.claim().id).status).isEqualTo("Pending Information");
		assertThat(this.scenario.intake(created.claim().id).orElseThrow())
			.returns(null, state -> state.reviewRunId)
			.satisfies(state -> assertThat(state.requestedItems).containsExactly(MissingItem.LOCATION));
		assertThat(this.scenario.waitingRun(created.claim().id)).isEmpty();
		assertThat(oldRun.status()).isEqualTo(WorkflowStatus.CANCELLED);
		assertThat(this.scenario.awaitReplies(SENDER, 2))
			.last()
			.satisfies(reply -> assertThat(reply.body()).contains("location"));
	}

	@Test
	void aReplyThatFindsTheClaimBeingDecidedWaitsForTheDecisionThenGetsAStatusReply() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		var run = this.scenario.awaitWaitingRun(created.claim().id);
		var runId = this.scenario.claimReview(created.claim().id);

		var marker = marker();
		stubFollowUp(marker, COMPLETE_DETAILS);
		var id = this.scenario.submit(Mail.from(SENDER).inReplyTo(created.messageId()).body("One more thing. " + marker));

		// It goes back in the queue: still in the processing folder, not filed, and no reply yet
		Awaitility.await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).until(() -> true);
		assertThat(this.scenario.inFolder(MailFolder.PROCESSING)).hasSize(1);
		assertThat(this.scenario.replies(SENDER)).hasSize(1);

		this.scenario.publishUntilWoken(run, new ReviewDecided(created.claim().id, runId, new ReviewDecision(ReviewOutcome.READY, Set.of(), ReviewedFields.none())));

		this.scenario.awaitFiled(id);
		assertThat(this.scenario.claim(created.claim().id).status).isEqualTo("In Process");
		assertThat(this.scenario.awaitReplies(SENDER, 3))
			.hasSize(3)
			.last()
			.satisfies(reply -> assertThat(reply.body()).contains(STATUS_ANSWER));
	}

	@Test
	void aReplyWhoseAgentsFailLeavesTheReviewAndItsWaitingRunIntact() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		var run = this.scenario.awaitWaitingRun(created.claim().id);

		var marker = marker();
		this.stubs.failWhenAll(List.of(CLASSIFY, marker));
		this.scenario.awaitFailed(this.scenario.submit(Mail.from(SENDER).inReplyTo(created.messageId()).body("More. " + marker)));

		assertThat(run.status()).isEqualTo(WorkflowStatus.WAITING);
		assertThat(this.scenario.claim(created.claim().id).status).isEqualTo("Pending Review");
		assertThat(this.scenario.intake(created.claim().id).orElseThrow().reviewRunId).isEqualTo(run.id());

		this.scenario.decide(created.claim().id, ReviewOutcome.READY, Set.of());

		Awaitility.await().atMost(IntakeScenario.WAIT).until(() -> this.scenario.claim(created.claim().id).status.equals("In Process"));
	}

	@Test
	void aReplyWaitsWhileAWokenReviewRunAppliesItsDecision() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		var run = this.scenario.awaitWaitingRun(created.claim().id);
		var runId = this.scenario.claimReview(created.claim().id);
		// Hold the decision step before it changes anything, so the run is visibly working for a while
		doAnswer(invocation -> {
			Awaitility.await().pollDelay(DECISION_DELAY).atMost(DECISION_DELAY.plusSeconds(5)).until(() -> true);
			return invocation.callRealMethod();
		}).when(this.decisionSteps).applyReady(any(), any());

		this.scenario.publishUntilWoken(run, new ReviewDecided(created.claim().id, runId, new ReviewDecision(ReviewOutcome.READY, Set.of(), ReviewedFields.none())));

		var marker = marker();
		stubFollowUp(marker, COMPLETE_DETAILS);
		var id = this.scenario.submit(Mail.from(SENDER).inReplyTo(created.messageId()).body("One more thing. " + marker));

		// The woken run holds the sender, so the reply hasn't started: no model call mentions it yet
		Awaitility.await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).until(() -> true);
		assertThat(run.status()).isEqualTo(WorkflowStatus.RUNNING);
		assertThat(this.stubs.countRequestsFor(marker)).isZero();

		this.scenario.awaitFiled(id);

		assertThat(this.scenario.claim(created.claim().id).status).isEqualTo("In Process");
		assertThat(this.scenario.awaitReplies(SENDER, 3))
			.hasSize(3)
			.last()
			.satisfies(reply -> assertThat(reply.body()).contains(STATUS_ANSWER));
	}

	@Test
	void aFaultOutsideTheDecisionCatchHandsTheReviewToANewRun() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		var run = this.scenario.awaitWaitingRun(created.claim().id);
		// Not a WorkflowException, so the flow's tryCatch never sees it: the run faults after its wait. Only once: the second
		// decision runs the real step
		doThrow(new IllegalStateException("Unexpected"))
			.doCallRealMethod()
			.when(this.decisionSteps).markInProcess(any(), any(), any());

		this.scenario.decide(created.claim().id, ReviewOutcome.READY, Set.of());

		Awaitility.await().atMost(IntakeScenario.WAIT).until(() -> run.status() == WorkflowStatus.FAULTED);
		var replacement = Awaitility.await()
			.atMost(IntakeScenario.WAIT)
			.until(() -> this.scenario.waitingRun(created.claim().id).filter(waiting -> !waiting.id().equals(run.id())), Optional::isPresent)
			.orElseThrow();

		assertThat(this.scenario.claim(created.claim().id).status).isEqualTo("Pending Review");
		assertThat(this.scenario.intake(created.claim().id).orElseThrow())
			.returns(replacement.id(), state -> state.reviewRunId)
			.returns(null, state -> state.decidingRunId);
		assertThat(this.scenario.awaitReplies(SENDER, 2))
			.hasSize(2)
			.last()
			.satisfies(reply -> assertThat(reply.body()).contains("problem"));
		// The email was filed when the run began to wait, so there's nothing to move to the failed folder
		assertThat(this.scenario.inFolder(MailFolder.FAILED)).isEmpty();

		// The reviewer decides again, and the new run applies it without another model call
		var requestsBefore = this.stubs.requests().size();
		this.scenario.decide(created.claim().id, ReviewOutcome.READY, Set.of());

		Awaitility.await().atMost(IntakeScenario.WAIT).until(() -> this.scenario.claim(created.claim().id).status.equals("In Process"));
		assertThat(this.scenario.awaitReplies(SENDER, 3))
			.hasSize(3)
			.last()
			.satisfies(reply -> assertThat(reply.body()).contains(created.claim().claimNumber, "In Process"));
		assertThat(this.stubs.requests()).hasSize(requestsBefore);
	}

	@Test
	void aReplyAgainstAClaimThatStaysUnderDecisionEndsInTheFailedFolder() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		this.scenario.awaitWaitingRun(created.claim().id);
		this.scenario.claimReview(created.claim().id);

		var marker = marker();
		stubFollowUp(marker, COMPLETE_DETAILS);
		var id = this.scenario.submit(Mail.from(SENDER).inReplyTo(created.messageId()).body("Anyone there? " + marker));

		this.scenario.awaitFailed(id);

		assertThat(this.scenario.claim(created.claim().id).status).isEqualTo("Pending Review");
	}

	@Test
	void aFaultInTheDecisionStepPutsTheClaimBackAndTheSameRunWaitsAgain() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		var run = this.scenario.awaitWaitingRun(created.claim().id);
		// A fault before the commit, once; the second decision runs the real step
		doThrow(new IllegalStateException("The database is down"))
			.doCallRealMethod()
			.when(this.decisionSteps).applyReady(any(), any());
		Log.infof("DIAG fault test: claim=%s run=%s spy=%s stubbings=%s", created.claim().id, run.id(), System.identityHashCode(this.decisionSteps),
			Mockito.mockingDetails(this.decisionSteps).getStubbings());

		this.scenario.decide(created.claim().id, ReviewOutcome.READY, Set.of());

		var restored = Awaitility.await().atMost(IntakeScenario.WAIT).until(() -> this.scenario.waitingRun(created.claim().id), Optional::isPresent).orElseThrow();

		assertThat(restored.id()).isEqualTo(run.id());
		assertThat(this.scenario.claim(created.claim().id).status).isEqualTo("Pending Review");
		assertThat(this.scenario.intake(created.claim().id).orElseThrow().decidingRunId).isNull();
		assertThat(this.scenario.replies(SENDER)).hasSize(1);

		this.scenario.decide(created.claim().id, ReviewOutcome.READY, Set.of());

		Awaitility.await().atMost(IntakeScenario.WAIT).until(() -> this.scenario.claim(created.claim().id).status.equals("In Process"));
		assertThat(this.scenario.awaitReplies(SENDER, 2)).hasSize(2);
	}

	@Test
	void everyEndedOrCanceledRunLeavesNoFlowRows() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		var runId = this.scenario.awaitWaitingRun(created.claim().id).id();
		this.scenario.decide(created.claim().id, ReviewOutcome.READY, Set.of());

		Awaitility.await().atMost(IntakeScenario.WAIT).until(() -> this.scenario.instanceRows() == 0);
		assertThat(this.scenario.taskRows(runId)).isZero();
	}
}
