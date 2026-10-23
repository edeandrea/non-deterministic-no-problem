package org.parasol.intake.workflow;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import org.parasol.claim.model.Claim;
import org.parasol.intake.IntakeClaimStatus;
import org.parasol.intake.config.IntakeConfig;
import org.parasol.intake.MissingItem;
import org.parasol.intake.agent.triage.ClaimResolver;
import org.parasol.intake.model.ClaimIntakeState;
import org.parasol.intake.model.PendingClaim;
import org.parasol.intake.workflow.IntakeState.Disposition;
import org.parasol.intake.workflow.mail.IntakeEmailReader;


/**
 * The steps before the agents: set the run up from what the starter matched, and, for an email that matched no claim from
 * a sender with pending ones, ask {@link ClaimResolver} which of them it's about.
 */
@ApplicationScoped
class ClaimMatchingSteps {
	private static final List<String> PENDING_STATUSES = Stream.of(IntakeClaimStatus.PENDING_INFORMATION, IntakeClaimStatus.PENDING_REVIEW)
		.map(IntakeClaimStatus::label)
		.toList();

	private final IntakeEmailReader emails;
	private final ClaimResolver claimResolver;
	private final IntakeConfig config;

	ClaimMatchingSteps(IntakeEmailReader emails, ClaimResolver claimResolver, IntakeConfig config) {
		this.emails = emails;
		this.claimResolver = claimResolver;
		this.config = config;
	}

	@Transactional(TxType.REQUIRES_NEW)
	IntakeState initialize(IntakeRun run) {
		return run.resumesReview() ? resumeReview(run) : initializeForEmail(run);
	}

	// A run that takes over a review: it only waits for the reviewer's decision, so the routing sends it straight there
	private static IntakeState resumeReview(IntakeRun run) {
		return IntakeState.start(run.messageId(), run.sender())
			.withClaim(run.claimId(), true)
			.withApplied(run.claimId(), Set.of(), true);
	}

	private static IntakeState initializeForEmail(IntakeRun run) {
		var state = IntakeState.start(run.messageId(), run.sender());
		var claim = Stream.ofNullable(run.claimId())
			.map(id -> Claim.<Claim>findById(id))
			.findFirst();
		var isWrongSender = claim.isPresent() && !run.senderMatches();
		var pending = claim.isPresent() ? List.<Claim>of() : Claim.findByEmailAddressAndStatus(run.sender(), PENDING_STATUSES);
		var numbers = pending.stream()
			.map(pendingClaim -> pendingClaim.claimNumber)
			.toList();

		return state
			.withClaim(claim.map(matched -> matched.id).orElse(null), claim.filter(ClaimMatchingSteps::isPendingReview).isPresent())
			.withDisposition(isWrongSender ? Disposition.WRONG_SENDER : Disposition.CONTINUE)
			.withPendingClaims(!pending.isEmpty(), numbers);
	}

	IntakeState resolveClaim(IntakeState state) {
		var email = this.emails.require(state.messageId());
		var offered = pendingClaimsOf(state.sender());
		var numbers = offered.stream()
			.map(claim -> claim.pending().claimNumber())
			.toList();
		var text = CorrespondenceText.cap(CorrespondenceText.firstEmail(email.subject(), email.strippedBody()), this.config.limits().maxBodyCharacters());
		var resolution = this.claimResolver.resolveClaim(text, offered.stream().map(Offered::pending).toList())
			.limitToOfferedClaims(numbers);

		return switch (resolution.kind()) {
			case EXISTING -> offered.stream()
				.filter(claim -> claim.pending().claimNumber().equals(resolution.claimNumber()))
				.findFirst()
				.map(claim -> state.withClaim(claim.id(), claim.isPendingReview()).withPendingClaims(false, numbers))
				.orElseThrow();
			case NEW_INCIDENT -> state.withPendingClaims(false, numbers);
			case UNSURE -> state.withPendingClaims(false, numbers).withDisposition(Disposition.WHICH_CLAIM);
		};
	}

	// Its own transaction, committed before the resolver's LLM call
	@Transactional(TxType.REQUIRES_NEW)
	List<Offered> pendingClaimsOf(String sender) {
		return Claim.findByEmailAddressAndStatus(sender, PENDING_STATUSES)
			.stream()
			.map(claim -> new Offered(claim.id, isPendingReview(claim), new PendingClaim(claim.claimNumber, claim.summary, requestedItemsOf(claim))))
			.toList();
	}

	private static Set<MissingItem> requestedItemsOf(Claim claim) {
		return ClaimIntakeState.findForClaim(claim.id)
			.map(intake -> Set.copyOf(intake.requestedItems))
			.orElse(Set.of());
	}

	private static boolean isPendingReview(Claim claim) {
		return IntakeClaimStatus.PENDING_REVIEW.label().equals(claim.status);
	}

	record Offered(long id, boolean isPendingReview, PendingClaim pending) {
	}
}
