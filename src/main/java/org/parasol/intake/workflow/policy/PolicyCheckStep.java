package org.parasol.intake.workflow.policy;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional.TxType;
import jakarta.transaction.Transactional;

import org.parasol.claim.model.Claim;
import org.parasol.intake.model.ClaimIntakeState;
import org.parasol.intake.model.IntakeOutcome.NewClaim;
import org.parasol.intake.model.IntakeOutcome.PendingClaimUpdate;
import org.parasol.intake.workflow.IntakeState;
import org.parasol.intake.workflow.IntakeState.Disposition;
import org.parasol.intake.workflow.mail.IntakeEmailReader;

import io.quarkus.logging.Log;

/**
 * Checks the policy number before any claim is created or changed.
 * <ul>
 *   <li>stated, and on claims of the same customer: reuse it</li>
 *   <li>stated, on another customer's claim: reject, so nothing is created or changed</li>
 *   <li>stated, on no claim: use it</li>
 *   <li>not stated: generate one, and mark it generated</li>
 *   <li>on an update: a stated number replaces the claim's only if we generated that one; a number the customer gave is never
 *   changed</li>
 * </ul>
 */
@ApplicationScoped
public class PolicyCheckStep {
	private final IntakeEmailReader emails;

	PolicyCheckStep(IntakeEmailReader emails) {
		this.emails = emails;
	}

	public IntakeState check(IntakeState state) {
		return switch (state.outcome()) {
			case NewClaim newClaim -> checkNewClaim(state, newClaim);
			case PendingClaimUpdate update -> checkUpdate(state, update);
			default -> state;
		};
	}

	private IntakeState checkNewClaim(IntakeState state, NewClaim newClaim) {
		var stated = Optional.ofNullable(newClaim.extraction().details().policyNumber())
			.map(String::strip)
			.filter(number -> !number.isEmpty());

		return stated
			.map(number -> apply(state, number))
			.orElseGet(() -> state.withPolicy(unusedPolicyNumber(), true));
	}

	private IntakeState checkUpdate(IntakeState state, PendingClaimUpdate update) {
		var stated = Optional.ofNullable(update.extraction().details().policyNumber())
			.map(String::strip)
			.filter(number -> !number.isEmpty());
		var claim = Optional.ofNullable(state.claimId()).flatMap(this::currentPolicy);

		return stated
			.filter(number -> claim.filter(current -> !number.equals(current.policyNumber())).isPresent())
			.map(number -> claim.filter(Current::generated)
				.map(_ -> apply(state, number))
				.orElseGet(() -> {
					Log.debugf("Ignoring policy number %s on an update: the claim's own was given by the customer", number);
					return state;
				}))
			.orElse(state);
	}

	private IntakeState apply(IntakeState state, String statedNumber) {
		var email = this.emails.require(state.messageId());
		var holders = holdersOf(statedNumber);

		return SameCustomerRule.isInconsistent(holders, email)
			? state.withDisposition(Disposition.POLICY_REJECTED)
			: state.withPolicy(statedNumber, false);
	}

	@Transactional(TxType.REQUIRES_NEW)
	String unusedPolicyNumber() {
		return PolicyNumbers.generateUnused();
	}

	@Transactional(TxType.REQUIRES_NEW)
	Optional<Current> currentPolicy(long claimId) {
		return Claim.<Claim>findByIdOptional(claimId)
			.map(found -> new Current(found.policyNumber, ClaimIntakeState.findForClaim(found.id).map(intake -> intake.policyNumberGenerated).orElse(false)));
	}

	@Transactional(TxType.REQUIRES_NEW)
	List<SameCustomerRule.Holder> holdersOf(String policyNumber) {
		return Claim.<Claim>list("policyNumber", policyNumber)
			.stream()
			.map(claim -> new SameCustomerRule.Holder(claim.emailAddress, claim.clientName))
			.toList();
	}

	record Current(String policyNumber, boolean generated) {
	}
}
