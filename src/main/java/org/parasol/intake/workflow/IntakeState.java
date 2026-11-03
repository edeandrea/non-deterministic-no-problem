package org.parasol.intake.workflow;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.parasol.intake.MissingItem;
import org.parasol.intake.model.IntakeOutcome;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * What every step of {@link ClaimIntakeFlow} hands to the next. JSON-safe (Flow persists it after every step): ids and
 * small values only, no {@code Optional}, no bytes and no email text.
 *
 * @param messageId The {@code Message-ID} of the email being processed
 * @param sender The sender's address
 * @param claimId The matched claim, or the one this run created; {@code null} until there is one
 * @param disposition Whether the run is still on the main path, and if not, why
 * @param mayResolve Whether the email matched no claim but its sender has pending ones, so {@code resolveClaim} should try
 * @param pendingClaimNumbers The sender's pending claim numbers, offered to {@code resolveClaim} and listed in the which-claim reply
 * @param outcome What the agents decided, or {@code null} before they ran
 * @param policyNumber The policy number for the claim to create, or to put on an updated claim; {@code null} if unchanged
 * @param policyNumberGenerated Whether {@code policyNumber} was generated
 * @param missingItems What the claim still lacks after this email, in template order
 * @param wasPendingReview Whether the matched claim was in {@code Pending Review} when the email arrived
 * @param needsReview Whether the claim is now complete and waits for a reviewer
 * @param receivedAt When the mailbox received the email, as an ISO-8601 instant, or {@code null} if unknown; {@code FileEmailStep} measures
 *   the email's latency from it
 */
public record IntakeState(
	String messageId,
	String sender,
	Long claimId,
	Disposition disposition,
	boolean mayResolve,
	List<String> pendingClaimNumbers,
	IntakeOutcome outcome,
	String policyNumber,
	boolean policyNumberGenerated,
	Set<MissingItem> missingItems,
	boolean wasPendingReview,
	boolean needsReview,
	String receivedAt) {

	/**
	 * Why a run is on, or left, the main path.
	 */
	public enum Disposition {
		/**
		 * The main path.
		 */
		CONTINUE,

		/**
		 * The email names a claim that isn't the sender's.
		 */
		WRONG_SENDER,

		/**
		 * The email could belong to several of the sender's pending claims and the resolver couldn't tell which.
		 */
		WHICH_CLAIM,

		/**
		 * The stated policy number belongs to another customer.
		 */
		POLICY_REJECTED,

		/**
		 * The claim is being decided; the email goes back in the sender's queue.
		 */
		REQUEUE
	}

	/**
	 * Copies the lists and sets, so the state can't change after a step hands it on; a {@code null} one becomes empty.
	 */
	public IntakeState {
		pendingClaimNumbers = (pendingClaimNumbers == null) ? List.of() : List.copyOf(pendingClaimNumbers);
		missingItems = (missingItems == null) ? Set.of() : Set.copyOf(missingItems);
	}

	static IntakeState start(String messageId, String sender, String receivedAt) {
		return new IntakeState(messageId, sender, null, Disposition.CONTINUE, false, List.of(), null, null, false, Set.of(), false, false, receivedAt);
	}

	/**
	 * Sets the claim the run works on.
	 *
	 * @param newClaimId The matched claim, or the one this run created
	 * @param newWasPendingReview Whether that claim was in {@code Pending Review} when the email arrived
	 * @return A copy with the claim set
	 */
	public IntakeState withClaim(Long newClaimId, boolean newWasPendingReview) {
		return new IntakeState(this.messageId, this.sender, newClaimId, this.disposition, this.mayResolve, this.pendingClaimNumbers, this.outcome,
			this.policyNumber, this.policyNumberGenerated, this.missingItems, newWasPendingReview, this.needsReview, this.receivedAt);
	}

	/**
	 * Moves the run onto, or off, the main path.
	 *
	 * @param newDisposition Why the run is on, or left, the main path
	 * @return A copy with the disposition set
	 */
	public IntakeState withDisposition(Disposition newDisposition) {
		return new IntakeState(this.messageId, this.sender, this.claimId, newDisposition, this.mayResolve, this.pendingClaimNumbers, this.outcome,
			this.policyNumber, this.policyNumberGenerated, this.missingItems, this.wasPendingReview, this.needsReview, this.receivedAt);
	}

	/**
	 * Records the sender's pending claims, which {@code resolveClaim} chooses from when the email matched none.
	 *
	 * @param newMayResolve Whether {@code resolveClaim} should try to tell which of the claims the email belongs to
	 * @param numbers The sender's pending claim numbers
	 * @return A copy with the pending claims set
	 */
	public IntakeState withPendingClaims(boolean newMayResolve, List<String> numbers) {
		return new IntakeState(this.messageId, this.sender, this.claimId, this.disposition, newMayResolve, numbers, this.outcome,
			this.policyNumber, this.policyNumberGenerated, this.missingItems, this.wasPendingReview, this.needsReview, this.receivedAt);
	}

	/**
	 * Records what the agents decided.
	 *
	 * @param newOutcome The agents' outcome for the email
	 * @return A copy with the outcome set
	 */
	public IntakeState withOutcome(IntakeOutcome newOutcome) {
		return new IntakeState(this.messageId, this.sender, this.claimId, this.disposition, this.mayResolve, this.pendingClaimNumbers, newOutcome,
			this.policyNumber, this.policyNumberGenerated, this.missingItems, this.wasPendingReview, this.needsReview, this.receivedAt);
	}

	/**
	 * Records the policy number for the claim to create, or to put on an updated claim.
	 *
	 * @param newPolicyNumber The policy number
	 * @param generated Whether the number was generated rather than stated in the email
	 * @return A copy with the policy number set
	 */
	public IntakeState withPolicy(String newPolicyNumber, boolean generated) {
		return new IntakeState(this.messageId, this.sender, this.claimId, this.disposition, this.mayResolve, this.pendingClaimNumbers, this.outcome,
			newPolicyNumber, generated, this.missingItems, this.wasPendingReview, this.needsReview, this.receivedAt);
	}

	/**
	 * Records what applying the outcome did to the claim.
	 *
	 * @param newClaimId The claim that was created or updated
	 * @param missing What the claim still lacks after this email
	 * @param newNeedsReview Whether the claim is now complete and waits for a reviewer
	 * @return A copy with the applied result set
	 */
	public IntakeState withApplied(Long newClaimId, Set<MissingItem> missing, boolean newNeedsReview) {
		return new IntakeState(this.messageId, this.sender, newClaimId, this.disposition, this.mayResolve, this.pendingClaimNumbers, this.outcome,
			this.policyNumber, this.policyNumberGenerated, missing, this.wasPendingReview, newNeedsReview, this.receivedAt);
	}

	/**
	 * Whether the email names a claim that isn't the sender's. The four {@code is...} methods are the flow's routing predicates, named so
	 * its definition reads as the design does and a stack trace or breakpoint shows a name, not a lambda. {@code @JsonIgnore} on each keeps
	 * Jackson from persisting them as properties, which the record has no component for.
	 */
	@JsonIgnore
	public boolean isWrongSender() {
		return this.disposition == Disposition.WRONG_SENDER;
	}

	/**
	 * Whether the resolver couldn't tell which of the sender's pending claims the email belongs to.
	 */
	@JsonIgnore
	public boolean isWhichClaim() {
		return this.disposition == Disposition.WHICH_CLAIM;
	}

	/**
	 * Whether the stated policy number belongs to another customer.
	 */
	@JsonIgnore
	public boolean isPolicyRejected() {
		return this.disposition == Disposition.POLICY_REJECTED;
	}

	/**
	 * Whether the run goes on to apply its outcome: the claim isn't being decided, which would send the email back in the queue.
	 */
	@JsonIgnore
	public boolean isNotRequeued() {
		return this.disposition != Disposition.REQUEUE;
	}

	/**
	 * The missing items in the order the reply templates list them.
	 */
	@JsonIgnore
	public Set<MissingItem> orderedMissingItems() {
		return this.missingItems.isEmpty() ? Set.of() : EnumSet.copyOf(this.missingItems);
	}
}
