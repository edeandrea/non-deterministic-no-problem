package org.parasol.intake.model;

import java.time.Instant;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.Length;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.parasol.claim.model.Claim;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.quarkus.panache.common.Sort;

/**
 * Something an intake run has to be told, written by whichever application instance handled the request and delivered by the application instance that hosts the run
 * (a run lives in one JVM, and Flow's events don't cross application instances). It's written in the transaction that makes the decision, so the change
 * and the signal commit together or not at all, and it stays until the run has taken it.
 * <p>
 *   The payload is the signal's data as JSON, ids and the decision only: the same rule as the data Flow persists. Deleting the claim
 *   deletes its signals.
 * </p>
 */
@Entity
@Table(name = "intake_run_signal", indexes = @Index(name = "intake_run_signal_run_id_idx", columnList = "run_id"))
public class IntakeRunSignal extends PanacheEntity {
	@Column(nullable = false, updatable = false)
	public String runId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "claim_id", nullable = false, updatable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	public Claim claim;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	public Kind kind;

	@Column(nullable = false, updatable = false, length = Length.LONG32)
	public String payload;

	@Column(nullable = false, updatable = false)
	public Instant createdAt;

	/**
	 * How many times the signal was handed to the run.
	 */
	public int attempts;

	/**
	 * What a signal asks of a run.
	 */
	public enum Kind {
		/**
		 * A reviewer decided the review the run waits for. The payload is a {@code ReviewDecided}.
		 */
		REVIEW_DECIDED
	}

	/**
	 * Records a signal for a run. It's persisted in the caller's transaction.
	 *
	 * @param claim the claim the signal is about
	 * @param runId the run it's for
	 * @param kind what it asks
	 * @param payload its data, as JSON
	 * @return the signal
	 */
	public static IntakeRunSignal record(Claim claim, String runId, Kind kind, String payload) {
		var signal = new IntakeRunSignal();
		signal.claim = claim;
		signal.runId = runId;
		signal.kind = kind;
		signal.payload = payload;
		signal.createdAt = Instant.now();
		signal.persist();

		return signal;
	}

	/**
	 * The signals not yet taken by their run, oldest first.
	 *
	 * @return the signals
	 */
	public static List<IntakeRunSignal> listPending() {
		return IntakeRunSignal.list("", Sort.by("id"));
	}

	/**
	 * Removes the signals of a claim's run.
	 *
	 * @param claimId the claim id
	 * @param runId the run id
	 * @return how many were removed
	 */
	public static long deleteForRun(long claimId, String runId) {
		return IntakeRunSignal.delete("claim.id = ?1 and runId = ?2", claimId, runId);
	}
}