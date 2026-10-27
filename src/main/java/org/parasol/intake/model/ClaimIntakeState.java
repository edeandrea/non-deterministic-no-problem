package org.parasol.intake.model;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.parasol.claim.model.Claim;
import org.parasol.claim.model.IntakeClaimStatus;
import org.parasol.intake.MissingItem;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * What the email intake keeps about a claim it created or updated. Plumbing, not part of the claim: {@link Claim} doesn't
 * know this entity exists, so no claim fetch can load it. A seeded claim has no row until the intake first touches it.
 * <p>
 *   The row shares the claim's id and is deleted with it. Its {@link #version} is the optimistic lock a reply that
 *   supersedes a review and a reviewer's decision race on: both write this row.
 * </p>
 */
@Entity
@Table(name = "claim_intake_state")
public class ClaimIntakeState extends PanacheEntityBase {
	@Id
	public Long id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@MapsId
	@JoinColumn(name = "id")
	@OnDelete(action = OnDeleteAction.CASCADE)
	public Claim claim;

	@Version
	public long version;

	/**
	 * The id of the intake run waiting for a reviewer's decision, while the claim is {@code Pending Review}.
	 */
	public String reviewRunId;

	/**
	 * The id of the run a reviewer's decision is being applied to: the review claims it by moving {@link #reviewRunId} here.
	 */
	public String decidingRunId;

	/**
	 * The conversation id of the intake runs about the claim. Set when the intake creates the claim, never changed.
	 */
	@Column(updatable = false)
	public String intakeConversationId;

	/**
	 * Whether the claim's policy number was generated because the customer didn't state one.
	 */
	public boolean policyNumberGenerated;

	/**
	 * What the customer was asked to supply: the details a new claim lacked, or the items a reviewer checked. Empty unless the
	 * claim is {@code Pending Information}. The constant names are stored, not the labels.
	 */
	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "claim_requested_items", joinColumns = @JoinColumn(name = "claim_id"))
	@Column(name = "item", nullable = false)
	@Enumerated(EnumType.STRING)
	@OnDelete(action = OnDeleteAction.CASCADE)
	public Set<MissingItem> requestedItems = new HashSet<>();

	/**
	 * Whether this row's own bookkeeping says a review is open: the claim is {@code Pending Review}, {@code reviewRunId}
	 * is set, and no other decision is already in flight ({@code decidingRunId} unset). This alone isn't enough to know a
	 * decision would actually succeed: {@code reviewRunId} is set in the workflow's {@code applyOutcome} step, before the
	 * run has necessarily reached its {@code waitReview} wait and gone {@code WAITING} (it still sends the reply and
	 * files the email first). Use {@code org.parasol.intake.review.ClaimReviewService#isReadyForDecision} for the live
	 * check against the workflow.
	 *
	 * @return {@code true} if this row's bookkeeping says a review is open
	 */
	public boolean isOpenForReview() {
		return IntakeClaimStatus.PENDING_REVIEW.label().equals(this.claim.status)
			&& (this.reviewRunId != null)
			&& (this.decidingRunId == null);
	}

	/**
	 * Finds the intake state of a claim.
	 *
	 * @param claimId the claim id
	 * @return the state, or empty if the intake never touched the claim
	 */
	public static Optional<ClaimIntakeState> findForClaim(long claimId) {
		return findByIdOptional(claimId);
	}

	/**
	 * Finds the intake state of a claim, creating it if the claim has none. Persisted in the current transaction.
	 *
	 * @param claim the persisted claim
	 * @return the claim's state
	 */
	public static ClaimIntakeState forClaim(Claim claim) {
		return findForClaim(claim.id)
			.orElseGet(() -> {
				var state = new ClaimIntakeState();
				state.claim = claim;
				state.persist();

				return state;
			});
	}

	/**
	 * Creates the state of a claim the intake just created. The conversation id is set before the insert: it's never updated.
	 *
	 * @param claim the persisted claim
	 * @param conversationId the conversation id of the run that created the claim
	 * @return the persisted state
	 */
	public static ClaimIntakeState createFor(Claim claim, String conversationId) {
		var state = new ClaimIntakeState();
		state.claim = claim;
		state.intakeConversationId = conversationId;
		state.persist();

		return state;
	}
}
