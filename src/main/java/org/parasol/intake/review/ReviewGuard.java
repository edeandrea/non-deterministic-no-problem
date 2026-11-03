package org.parasol.intake.review;

import jakarta.persistence.LockModeType;

import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimNotFoundException;
import org.parasol.intake.model.ClaimIntakeState;

/**
 * The checks every change a reviewer makes to a claim starts with, in a transaction the caller owns: the claim exists, its
 * review is open ({@code Pending Review}, waiting for a decision, none in flight), and it's still at the version the
 * reviewer saw.
 */
final class ReviewGuard {
	private ReviewGuard() {
	}

	static ClaimIntakeState openReview(long claimId, long expectedVersion, LockModeType lock) {
		Claim.<Claim>findByIdOptional(claimId)
			.orElseThrow(() -> new ClaimNotFoundException(claimId));
		var state = ClaimIntakeState.<ClaimIntakeState>findByIdOptional(claimId, lock)
			.filter(ClaimIntakeState::isOpenForReview)
			.orElseThrow(() -> new ReviewNotOpenException(claimId));

		if (state.version != expectedVersion) {
			throw new ReviewLockLostException(claimId);
		}

		return state;
	}
}
