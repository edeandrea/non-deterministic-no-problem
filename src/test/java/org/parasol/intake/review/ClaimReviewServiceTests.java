package org.parasol.intake.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Set;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.parasol.intake.IntakeFlowTestBase;
import org.parasol.intake.IntakeScenario;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;

/**
 * How a reviewer's decision reaches the waiting run: published again until the run takes it, and given up on, with the review back to open, when it never does.
 */
@QuarkusTest
class ClaimReviewServiceTests extends IntakeFlowTestBase {
	@Inject
	ClaimReviewService service;

	@InjectSpy
	ReviewDecisionPublisher publisher;

	@Test
	void aDecisionTheRunMissedIsPublishedAgainUntilItIsTaken() {
		var claimId = createClaim(SENDER, COMPLETE_DETAILS).claim().id;
		this.scenario.awaitWaitingRun(claimId);
		var version = this.scenario.intake(claimId).orElseThrow().version;

		doNothing().doCallRealMethod().when(this.publisher).publish(any());

		this.service.submit(claimId, version, new ReviewDecision(ReviewOutcome.READY, Set.of(), ReviewedFields.none()));

		verify(this.publisher, times(2)).publish(any());
		await()
			.atMost(IntakeScenario.WAIT)
			.until(() -> this.scenario.claim(claimId).status.equals("In Process"));
	}

	@Test
	void aDecisionNobodyTakesPutsTheReviewBackToOpen() {
		var claimId = createClaim(SENDER, COMPLETE_DETAILS).claim().id;
		var run = this.scenario.awaitWaitingRun(claimId);
		var version = this.scenario.intake(claimId).orElseThrow().version;

		doNothing().when(this.publisher).publish(any());

		assertThatThrownBy(() -> this.service.submit(claimId, version, new ReviewDecision(ReviewOutcome.READY, Set.of(), ReviewedFields.none())))
			.isInstanceOf(ReviewRunNotWaitingException.class);

		var state = this.scenario.intake(claimId).orElseThrow();
		assertThat(state.reviewRunId).isEqualTo(run.id());
		assertThat(state.decidingRunId).isNull();
		assertThat(this.scenario.claim(claimId).status).isEqualTo("Pending Review");

		doCallRealMethod().when(this.publisher).publish(any());
		this.service.submit(claimId, this.scenario.intake(claimId).orElseThrow().version, new ReviewDecision(ReviewOutcome.READY, Set.of(), ReviewedFields.none()));
		await()
			.atMost(IntakeScenario.WAIT)
			.until(() -> this.scenario.claim(claimId).status.equals("In Process"));
	}
}
