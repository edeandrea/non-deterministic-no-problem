package org.parasol.intake.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class SenderStateTests {
	@Test
	void anIdleSenderStartsTheFirstEmailAtOnce() {
		var state = SenderState.IDLE.enqueue("<a>");

		assertThat(state.starting()).contains("<a>");
		assertThat(state.working()).contains(SenderState.STARTING);
		assertThat(state.queue()).isEmpty();
	}

	@Test
	void aBusySenderQueuesInOrderAndStartsNothing() {
		var state = SenderState.IDLE.enqueue("<a>").working("run-1").enqueue("<b>").enqueue("<c>");

		assertThat(state.starting()).isEmpty();
		assertThat(state.queue()).containsExactly("<b>", "<c>");
	}

	@Test
	void releasingTheWorkingRunStartsTheNextEmailOnce() {
		var released = SenderState.IDLE.enqueue("<a>").working("run-1").enqueue("<b>").enqueue("<c>").release("run-1");

		assertThat(released.starting()).contains("<b>");
		assertThat(released.queue()).containsExactly("<c>");
		assertThat(released.release("run-1").starting()).as("not the holder any more").isEmpty();
	}

	@Test
	void onlyTheWorkingRunReleasesTheSender() {
		var state = SenderState.IDLE.enqueue("<a>").working("run-2").enqueue("<b>");

		assertThat(state.release("run-1"))
			.returns(Optional.of("run-2"), SenderState::working)
			.returns(Optional.empty(), SenderState::starting)
			.returns(List.of("<b>"), SenderState::queue);
	}

	@Test
	void aRequeuedEmailGoesFirstAndStartsOnlyWhenTheRunReleases() {
		var requeued = SenderState.IDLE.enqueue("<a>").working("run-1").enqueue("<b>").requeueFirst("<a>");

		assertThat(requeued.starting()).isEmpty();
		assertThat(requeued.queue()).containsExactly("<a>", "<b>");
		assertThat(requeued.release("run-1").starting()).contains("<a>");
	}

	@Test
	void aWokenReviewRunHoldsAnIdleSenderButNotABusyOne() {
		var woken = SenderState.IDLE.markWorking("run-1").enqueue("<a>");

		assertThat(woken.starting()).as("held until the decision steps end").isEmpty();
		assertThat(woken.release("run-1").starting()).contains("<a>");
		assertThat(SenderState.IDLE.working("run-2").markWorking("run-1").working()).contains("run-2");
	}

	@Test
	void aSenderWithNoRunAndNoQueueIsIdle() {
		assertThat(SenderState.IDLE.enqueue("<a>").working("run-1").release("run-1").isIdle()).isTrue();
	}
}
