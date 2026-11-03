package org.parasol.intake.dispatch;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimCorrespondence;
import org.parasol.intake.mailbox.InboundEmail;
import org.parasol.intake.model.ClaimIntakeState;


/**
 * Matches an email to a claim in code, not by the LLM: a claim number in the subject or body, then the
 * {@code In-Reply-To} and {@code References} headers against the {@code Message-ID}s recorded for claims. A claim only
 * matches its own address, and only a matched claim lends its conversation id; anything else starts a new conversation.
 */
@ApplicationScoped
class ClaimMatcher {
	/**
	 * The result of matching an email.
	 *
	 * @param claimId The claim the email names, even if its sender isn't the claim's, or {@code null} if it names none
	 * @param senderMatches Whether the sender is the claim's address
	 * @param conversationId The matched claim's conversation id, otherwise a new one
	 */
	record ClaimMatch(Long claimId, boolean senderMatches, String conversationId) {
	}

	@Transactional(TxType.REQUIRES_NEW)
	ClaimMatch match(InboundEmail email) {
		var claim = ClaimReference.findClaimNumber(email.subject(), email.strippedBody())
			.flatMap(Claim::findByClaimNumber)
			.or(() -> ClaimCorrespondence.findClaimByMessageIds(email.referencedMessageIds()));
		var ownClaim = claim
			.filter(found -> email.fromAddress().filter(address -> address.equalsIgnoreCase(found.emailAddress)).isPresent());
		var conversationId = ownClaim
			.flatMap(found -> ClaimIntakeState.findForClaim(found.id))
			.map(state -> state.intakeConversationId)
			.orElseGet(() -> UUID.randomUUID().toString());

		return new ClaimMatch(claim.map(found -> found.id).orElse(null), ownClaim.isPresent(), conversationId);
	}
}
