package org.parasol.intake.observability;

import java.time.Duration;
import java.util.Optional;

import org.parasol.claim.model.IntakeClaimStatus;
import org.parasol.intake.review.ReviewOutcome;

/**
 * Something the email intake did, fired as a CDI event so the code that did it knows nothing about metrics. Every value has
 * a small, fixed set of possibilities, never a claim number, {@code Message-ID}, address or conversation id.
 */
public sealed interface IntakeActivity {
	/**
	 * An email and what became of it.
	 */
	record EmailHandled(EmailOutcome outcome) implements IntakeActivity {
	}

	/**
	 * A claim moved to a status. Observed after the transaction that moved it commits.
	 */
	record ClaimStatusChanged(IntakeClaimStatus status) implements IntakeActivity {
	}

	/**
	 * A reply went out, by the name of its template.
	 */
	record ReplySent(String template) implements IntakeActivity {
	}

	/**
	 * A reviewer's decision was applied. Observed after the transaction that applied it commits.
	 *
	 * @param decision The decision
	 * @param waited How long the claim waited for it, if known
	 */
	record ReviewDecisionApplied(ReviewOutcome decision, Optional<Duration> waited) implements IntakeActivity {
	}

	/**
	 * A run failed.
	 *
	 * @param stage The stage it failed in
	 * @param exception The simple name of the exception
	 * @param outcome Whether the run faulted or carried on
	 */
	record IntakeFailed(FailureStage stage, String exception, FailureOutcome outcome) implements IntakeActivity {
	}

	/**
	 * The inbox watcher's IDLE connection went up or down.
	 */
	record WatcherConnectionChanged(boolean connected) implements IntakeActivity {
	}

	/**
	 * The inbox watcher is connecting to the claims mailbox again.
	 */
	record WatcherReconnecting() implements IntakeActivity {
	}
}
