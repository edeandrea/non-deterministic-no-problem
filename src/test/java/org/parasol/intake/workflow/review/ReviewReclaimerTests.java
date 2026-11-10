package org.parasol.intake.workflow.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Set;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.parasol.intake.IntakeFlowTestBase;
import org.parasol.intake.IntakeScenario;
import org.parasol.intake.review.ReviewDecided;
import org.parasol.intake.review.ReviewDecision;
import org.parasol.intake.review.ReviewOutcome;
import org.parasol.intake.review.ReviewedFields;

import io.quarkus.test.junit.QuarkusTest;
import io.serverlessworkflow.impl.WorkflowInstance;
import io.serverlessworkflow.impl.WorkflowStatus;

/**
 * A new leader gives a review whose run is gone a new run. The run that's gone is simulated by canceling the real one: the claim still names it.
 */
@QuarkusTest
class ReviewReclaimerTests extends IntakeFlowTestBase {
	@Inject
	ReviewReclaimer reclaimer;

	@Test
	void aReviewWhoseRunIsGoneGetsANewRunThatTakesTheDecision() {
		var claimId = createClaim(SENDER, COMPLETE_DETAILS).claim().id;
		var deadRunId = goneRun(claimId);

		assertThat(this.reclaimer.reclaim()).isEqualTo(1);

		var run = this.scenario.awaitWaitingRun(claimId);
		var state = this.scenario.intake(claimId).orElseThrow();

		assertThat(run.id()).isNotEqualTo(deadRunId);
		assertThat(state.reviewRunId).isEqualTo(run.id());
		assertThat(state.decidingRunId).isNull();

		this.scenario.decide(claimId, ReviewOutcome.READY, Set.of());

		await()
			.atMost(IntakeScenario.WAIT)
			.until(() -> this.scenario.claim(claimId).status.equals("In Process"));
	}

	@Test
	void aDecisionAlreadyMadeForTheRunThatIsGoneIsAppliedByTheNewRunWithNoSecondDecision() {
		var claimId = createClaim(SENDER, COMPLETE_DETAILS).claim().id;
		var deadRunId = goneRun(claimId);
		this.scenario.claimReview(claimId);
		this.scenario.queueDecisionFor(claimId, deadRunId, new ReviewDecided(claimId, deadRunId, new ReviewDecision(ReviewOutcome.READY, Set.of(), ReviewedFields.none())));

		assertThat(this.reclaimer.reclaim()).isEqualTo(1);
		assertThat(this.scenario.intake(claimId).orElseThrow().decidingRunId).isNotEqualTo(deadRunId).isNotNull();

		await()
			.atMost(IntakeScenario.WAIT)
			.until(() -> this.scenario.claim(claimId).status.equals("In Process"));
		await()
			.atMost(IntakeScenario.WAIT)
			.until(() -> this.scenario.signalCount() == 0);
		assertThat(this.scenario.intake(claimId).orElseThrow().decidingRunId).isNull();
	}

	@Test
	void aReviewWhoseRunIsAliveIsNotTouched() {
		var claimId = createClaim(SENDER, COMPLETE_DETAILS).claim().id;
		var run = this.scenario.awaitWaitingRun(claimId);

		assertThat(this.reclaimer.reclaim()).isZero();
		assertThat(this.scenario.intake(claimId).orElseThrow().reviewRunId).isEqualTo(run.id());
		assertThat(run.status()).isEqualTo(WorkflowStatus.WAITING);
	}

	@Test
	void reclaimingTwiceStartsOneRunForTheClaim() {
		var claimId = createClaim(SENDER, COMPLETE_DETAILS).claim().id;
		goneRun(claimId);

		assertThat(this.reclaimer.reclaim()).isEqualTo(1);
		var newRunId = this.scenario.awaitWaitingRun(claimId).id();

		assertThat(this.reclaimer.reclaim()).isZero();
		assertThat(this.scenario.intake(claimId).orElseThrow().reviewRunId).isEqualTo(newRunId);
	}

	// Cancels the run waiting for the claim's review: the claim keeps naming it, as it would a run whose application instance died
	@SuppressWarnings("resource")
	private String goneRun(long claimId) {
		var run = this.scenario.awaitWaitingRun(claimId);

		run.cancel();
		await()
			.atMost(IntakeScenario.WAIT)
			.until(() -> run.status() != WorkflowStatus.WAITING);

		return run.id();
	}
}
