package org.parasol.intake.workflow.review;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional.TxType;
import jakarta.transaction.Transactional;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.OptimisticLockException;

import org.parasol.claim.model.Claim;
import org.parasol.intake.IntakeClaimStatus;
import org.parasol.intake.model.ClaimIntakeState;
import org.parasol.intake.model.IntakeOutcome;
import org.parasol.intake.workflow.ClaimIntakeFlow;
import org.parasol.intake.workflow.EmailRequeued;
import org.parasol.intake.workflow.IntakeState;
import org.parasol.intake.workflow.IntakeState.Disposition;

import io.quarkus.logging.Log;
import io.serverlessworkflow.impl.WorkflowInstance;

/**
 * A reply to a claim that waits for review supersedes that review: the claim's {@code reviewRunId} is cleared under the
 * claim's optimistic lock, then the waiting run is canceled.
 * <p>
 *   If the claim is already being decided (its {@code decidingRunId} is set, or a reviewer's decision wins the lock) nothing
 *   changes: the email stays in the processing folder and goes back to the front of the sender's queue, to be handled again
 *   under the claim's new status.
 * </p>
 */
@ApplicationScoped
public class SupersedeStep {
	private final Instance<ClaimIntakeFlow> flow;
	private final Event<EmailRequeued> emailRequeued;

	SupersedeStep(Instance<ClaimIntakeFlow> flow, Event<EmailRequeued> emailRequeued) {
		this.flow = flow;
		this.emailRequeued = emailRequeued;
	}

	public IntakeState supersede(IntakeState state) {
		var isUpdate = (state.outcome() instanceof IntakeOutcome.PendingClaimUpdate);

		return (isUpdate && (state.claimId() != null)) ? supersedeReview(state) : state;
	}

	private IntakeState supersedeReview(IntakeState state) {
		var result = tryClearReview(state.claimId());

		return switch (result) {
			case Result.Superseded(var oldRunId) -> {
				oldRunId.ifPresent(this::cancelWaitingRun);
				yield state;
			}
			case Result.Requeue ignored -> {
				Log.debugf("Claim %d is being decided: putting %s back in the queue of %s", state.claimId(), state.messageId(), state.sender());
				this.emailRequeued.fire(new EmailRequeued(state.messageId(), state.sender()));
				yield state.withDisposition(Disposition.REQUEUE);
			}
		};
	}

	private Result tryClearReview(long claimId) {
		try {
			return clearReviewUnlessDecided(claimId);
		}
		catch (RuntimeException e) {
			return Stream.iterate(e, Objects::nonNull, Throwable::getCause)
				.anyMatch(OptimisticLockException.class::isInstance)
				? new Result.Requeue()
				: sneakyRethrow(e);
		}
	}

	// Its own transaction: losing the claim's lock to a reviewer's decision fails it (OptimisticLockException at the flush),
	// which tryClearReview turns into a requeue
	@Transactional(TxType.REQUIRES_NEW)
	Result clearReviewUnlessDecided(long claimId) {
		var claim = Claim.<Claim>findById(claimId);
		var state = ClaimIntakeState.findForClaim(claimId);
		var isDecided = state.filter(intake -> intake.decidingRunId != null).isPresent()
			|| !(IntakeClaimStatus.PENDING_REVIEW.label().equals(claim.status) || IntakeClaimStatus.PENDING_INFORMATION.label().equals(claim.status));

		return isDecided ? new Result.Requeue() : clear(state);
	}

	private static Result sneakyRethrow(RuntimeException e) {
		throw e;
	}

	// The version of the state row is the lock: a reviewer's decision claiming the review writes the same row
	private static Result clear(Optional<ClaimIntakeState> state) {
		var oldRunId = state.map(intake -> intake.reviewRunId);
		state.ifPresent(intake -> {
			intake.reviewRunId = null;
			ClaimIntakeState.flush();
		});

		return new Result.Superseded(oldRunId);
	}

	// The flow's definition is shared by every run: it must not be closed
	@SuppressWarnings("resource")
	private void cancelWaitingRun(String oldRunId) {
		var canceled = this.flow.get()
			.definition()
			.activeInstance(oldRunId)
			.map(WorkflowInstance::cancel)
			.orElse(false);

		Log.debugf("Canceled the run %s waiting for review: %s", oldRunId, canceled);
	}

	sealed interface Result {
		record Superseded(Optional<String> oldRunId) implements Result {
		}

		record Requeue() implements Result {
		}
	}
}
