package org.parasol.intake.workflow.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.Set;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.parasol.intake.IntakeFlowTestBase;
import org.parasol.intake.IntakeScenario;
import org.parasol.intake.review.ClaimReviewService;
import org.parasol.intake.review.ReviewDecided;
import org.parasol.intake.review.ReviewDecision;
import org.parasol.intake.review.ReviewDecisionPublisher;
import org.parasol.intake.review.ReviewOutcome;
import org.parasol.intake.review.ReviewRunNotWaitingException;
import org.parasol.intake.review.ReviewedFields;
import org.parasol.intake.workflow.review.ReviewDecisionSteps;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;

/**
 * A reviewer's decision travels as a row in {@code intake_run_signal}, written with the claim's change and handed to the run by the
 * relay of the application instance that hosts it. Exercised through {@link ClaimReviewService}, the way the endpoint does; the endpoint's own checks are in
 * {@code ClaimReviewResourceTests}.
 */
@QuarkusTest
class RunSignalRelayTests extends IntakeFlowTestBase {
	private static final ReviewDecision READY = new ReviewDecision(ReviewOutcome.READY, Set.of(), ReviewedFields.none());

	@Inject
	ClaimReviewService service;

	@Inject
	RunSignalRelay relay;

	@InjectSpy
	ReviewDecisionPublisher publisher;

	@InjectSpy
	ReviewDecisionSteps decisionSteps;

	@Test
	void aDecisionIsDeliveredToTheRunAndItsSignalIsDeleted() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		this.scenario.awaitWaitingRun(created.claim().id);

		this.service.submit(created.claim().id, version(created.claim().id), READY);

		awaitInProcess(created.claim().id);
		await().atMost(IntakeScenario.WAIT).until(() -> this.scenario.signalCount() == 0);
	}

	@Test
	void aLostEventIsPublishedAgainUntilTheRunHasTakenIt() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		this.scenario.awaitWaitingRun(created.claim().id);
		// The run says it's waiting a few statements before its listen registers a consumer, so an early publish can be lost: drop the first
		doNothing().doCallRealMethod().when(this.publisher).publish(any());

		this.service.submit(created.claim().id, version(created.claim().id), READY);

		this.relay.deliver();

		awaitInProcess(created.claim().id);
		verify(this.publisher, atLeast(2)).publish(any());
		await().atMost(IntakeScenario.WAIT).until(() -> this.scenario.signalCount() == 0);
	}

	@Test
	void aSignalForARunThisJvmDoesNotHostStaysPendingAndIsDeletedOnceTheRunHasTakenIt() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		this.scenario.awaitWaitingRun(created.claim().id);
		var claimId = created.claim().id;
		var elsewhere = new ReviewDecided(claimId, "01HRUNHOSTEDELSEWHERE00000", READY);

		this.scenario.queueDecisionFor(claimId, elsewhere.runId(), elsewhere);
		this.relay.deliver();
		this.relay.deliver();

		assertThat(this.scenario.signalCount()).isEqualTo(1);
		verify(this.publisher, never()).publish(any());

		// The application instance hosting that run applied the decision: nothing names the run any more
		this.scenario.releaseDecision(claimId);
		this.relay.deliver();

		assertThat(this.scenario.signalCount()).isZero();
	}

	@Test
	void aRunThatHasWaitedThreeTimesStillAcceptsADecision() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		var claimId = created.claim().id;
		this.scenario.awaitWaitingRun(claimId);
		// Two faults before the commit put the claim back in review, and the run waits a third time. Flow can't read such a run back
		doThrow(new IllegalStateException("The database is down"))
			.doThrow(new IllegalStateException("The database is down"))
			.doCallRealMethod()
			.when(this.decisionSteps).applyReady(any(), any());

		for (var attempt = 1; attempt <= 2; attempt++) {
			await().atMost(IntakeScenario.WAIT).until(() -> isReady(claimId));
			this.service.submit(claimId, version(claimId), READY);
			await().atMost(IntakeScenario.WAIT).until(() -> isReady(claimId) && (this.scenario.signalCount() == 0));
			assertThat(this.scenario.claim(claimId).status).as("after fault %d", attempt).isEqualTo("Pending Review");
		}

		this.service.submit(claimId, version(claimId), READY);

		awaitInProcess(claimId);
	}

	@Test
	void aRunThatHasNotReachedItsWaitRejectsTheDecisionAndLeavesNothingBehind() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		var claimId = created.claim().id;
		this.scenario.awaitWaitingRun(claimId);
		this.scenario.forgetWaiting(claimId);
		var version = version(claimId);

		assertThatThrownBy(() -> this.service.submit(claimId, version, READY)).isInstanceOf(ReviewRunNotWaitingException.class);

		var state = this.scenario.intake(claimId).orElseThrow();
		assertThat(state.reviewRunId).isNotNull();
		assertThat(state.decidingRunId).isNull();
		assertThat(this.scenario.signalCount()).isZero();
	}

	private boolean isReady(long claimId) {
		return this.scenario.isReadyForDecision(claimId, this.service);
	}

	private long version(long claimId) {
		return this.scenario.intake(claimId).orElseThrow().version;
	}

	private void awaitInProcess(long claimId) {
		await()
			.atMost(IntakeScenario.WAIT)
			.pollInterval(Duration.ofMillis(250))
			.until(() -> this.scenario.claim(claimId).status.equals("In Process"));
	}
}
