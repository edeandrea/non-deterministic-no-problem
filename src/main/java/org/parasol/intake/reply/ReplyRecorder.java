package org.parasol.intake.reply;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimCorrespondence;
import org.parasol.intake.config.IntakeConfig;

import io.quarkus.logging.Log;

/**
 * Records the replies about a claim that {@link IntakeReplySender} has sent, so the claim page can show the whole
 * conversation.
 */
@ApplicationScoped
class ReplyRecorder {
	private final String sender;

	ReplyRecorder(IntakeConfig config) {
		this.sender = config.address();
	}

	// Its own transaction: the sender calls it from a workflow step that holds none, and it must commit before the step moves on
	@Transactional(TxType.REQUIRES_NEW)
	void record(String claimNumber, SentReply reply) {
		Claim.findByClaimNumber(claimNumber)
			.ifPresentOrElse(
				claim -> ClaimCorrespondence.recordReply(claim, reply.messageId(), this.sender, reply.subject(), reply.text()),
				() -> Log.warnf("Sent a reply about claim %s, but there's no such claim to record it against", claimNumber));
	}
}
