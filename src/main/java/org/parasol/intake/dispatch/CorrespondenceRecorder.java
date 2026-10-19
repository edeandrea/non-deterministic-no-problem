package org.parasol.intake.dispatch;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import org.parasol.claim.model.ClaimCorrespondence;
import org.parasol.claim.model.CorrespondenceDirection;
import org.parasol.intake.mailbox.InboundEmail;

/**
 * Records every email the intake accepts. The unique {@code Message-ID} is the duplicate check: insert and catch the
 * violation, so two concurrent copies can't both pass.
 */
@ApplicationScoped
class CorrespondenceRecorder {
	/**
	 * Records an email.
	 *
	 * @param email The email
	 * @param messageId Its {@code Message-ID}
	 * @return {@code true} if it was new, {@code false} if that {@code Message-ID} was already recorded
	 */
	boolean recordIfNew(InboundEmail email, String messageId) {
		try {
			insert(email, messageId);

			return true;
		}
		catch (RuntimeException e) {
			// Anything but a duplicate is a real failure
			if (!isRecorded(messageId)) {
				throw e;
			}

			return false;
		}
	}

	@Transactional(TxType.REQUIRES_NEW)
	void insert(InboundEmail email, String messageId) {
		var correspondence = new ClaimCorrespondence();
		correspondence.messageId = messageId;
		correspondence.direction = CorrespondenceDirection.INBOUND;
		correspondence.sentDate = email.sentDate();
		correspondence.sender = email.fromAddress().orElse(null);
		correspondence.strippedText = email.strippedBody();
		correspondence.persistAndFlush();
	}

	@Transactional(TxType.REQUIRES_NEW)
	boolean isRecorded(String messageId) {
		return ClaimCorrespondence.findByMessageId(messageId).isPresent();
	}
}
