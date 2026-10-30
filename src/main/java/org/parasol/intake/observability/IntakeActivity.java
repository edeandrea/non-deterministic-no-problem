package org.parasol.intake.observability;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.parasol.claim.model.IntakeClaimStatus;
import org.parasol.intake.MissingItem;
import org.parasol.intake.mailbox.SkippedAttachment;
import org.parasol.intake.model.EmailType;
import org.parasol.intake.review.ReviewOutcome;

/**
 * Something the email intake did, fired as a CDI event so the code that did it knows nothing about metrics. Every value has
 * a small, fixed set of possibilities, never a claim number, {@code Message-ID}, address or conversation id.
 */
public sealed interface IntakeActivity {
	/**
	 * An email and what became of it.
	 *
	 * @param outcome What became of it
	 * @param latency How long it took from the mailbox receiving the email to leaving the processing folder, if known. Not set for an
	 *   email that was already filed (its latency was counted when it was filed)
	 */
	record EmailHandled(EmailOutcome outcome, Optional<Duration> latency) implements IntakeActivity {
	}

	/**
	 * An email the intake accepted (it was not an auto-reply and not a duplicate).
	 *
	 * @param sizeBytes The size of the message
	 * @param keptImages How many image attachments were kept
	 * @param skippedReasons Why each skipped attachment was skipped
	 */
	record EmailAccepted(long sizeBytes, int keptImages, List<SkippedAttachment.Reason> skippedReasons) implements IntakeActivity {
		public EmailAccepted {
			skippedReasons = List.copyOf(skippedReasons);
		}
	}

	/**
	 * What claim matching decided for an email, once per evaluation: a requeued email is evaluated again.
	 */
	record EmailMatched(MatcherResult result) implements IntakeActivity {
	}

	/**
	 * What the classifier labeled an email, and what the code had matched before it ran, once per evaluation.
	 */
	record EmailClassified(EmailType emailType, MatchedClaimKind matchedClaim) implements IntakeActivity {
	}

	/**
	 * A new claim was extracted from an email. Observed after the transaction that stored it commits.
	 *
	 * @param completeness The share of the required fields that were filled, from 0 to 1
	 * @param missingItems The items the claim lacks
	 */
	record NewClaimExtracted(double completeness, Set<MissingItem> missingItems) implements IntakeActivity {
		public NewClaimExtracted {
			missingItems = Set.copyOf(missingItems);
		}
	}

	/**
	 * A claim became complete: a reviewer marked it ready. Observed after the transaction that did it commits.
	 *
	 * @param timeToComplete From the first inbound email's arrival to now
	 * @param inboundEmails How many inbound emails the claim's correspondence holds
	 */
	record ClaimCompleted(Duration timeToComplete, long inboundEmails) implements IntakeActivity {
	}

	/**
	 * A call to the mail server finished.
	 *
	 * @param protocol The protocol it spoke
	 * @param operation What it did, e.g. {@code list} or {@code send}
	 * @param failed Whether it threw
	 * @param duration How long it took
	 */
	record MailClientCompleted(MailClientSpan.Protocol protocol, String operation, boolean failed, Duration duration) implements IntakeActivity {
	}

	/**
	 * A call to the claims mailbox got its lock.
	 *
	 * @param mode Which side of the lock it took
	 * @param waited How long it waited
	 */
	record MailboxLockAcquired(MailboxLockMode mode, Duration waited) implements IntakeActivity {
	}

	/**
	 * The number of emails waiting in the INBOX was measured.
	 */
	record InboxDepthMeasured(int messages) implements IntakeActivity {
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
	 * @param agreement Whether the reviewer accepted what the agents produced
	 * @param waited How long the claim waited for it, if known
	 */
	record ReviewDecisionApplied(ReviewOutcome decision, ReviewAgreement agreement, Optional<Duration> waited) implements IntakeActivity {
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
