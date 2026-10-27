package org.parasol.intake.review;

import static java.util.function.Predicate.not;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional.TxType;
import jakarta.transaction.Transactional;

import org.parasol.intake.agent.extraction.ClaimSentimentAgent;
import org.parasol.intake.config.IntakeConfig;
import org.parasol.intake.workflow.CorrespondenceText;

import io.quarkus.logging.Log;

/**
 * Assesses the claimant's sentiment again for a reviewer, from the claim's whole correspondence (its stored body), and
 * stores it on the claim. Only the sentiment changes: no other field, and nothing in the claim's run.
 * <p>
 *   The model is called outside any transaction. The claim's review is checked before the call and again, with the
 *   claim's intake state locked, before the result is stored, so a customer reply or a decision that lands during the
 *   call wins and the result is discarded.
 * </p>
 */
@ApplicationScoped
public class ClaimSentimentReassessor {
	private final ClaimSentimentAgent agent;
	private final IntakeConfig config;

	ClaimSentimentReassessor(ClaimSentimentAgent agent, IntakeConfig config) {
		this.agent = agent;
		this.config = config;
	}

	/**
	 * Assesses a claim's sentiment again and stores it.
	 *
	 * @param claimId the claim under review
	 * @param expectedVersion the {@code ClaimIntakeState} version the reviewer saw
	 * @return the new sentiment
	 * @throws org.parasol.claim.model.ClaimNotFoundException if the claim doesn't exist
	 * @throws ReviewNotOpenException if the claim has no review open (it isn't {@code Pending Review}, or a decision is in flight)
	 * @throws ReviewLockLostException if the claim changed since the reviewer saw it, before or during the assessment
	 * @throws SentimentAssessmentFailedException if the model failed or gave no assessment
	 */
	public String reassess(long claimId, long expectedVersion) {
		var sentiment = assess(claimId, correspondenceUnderReview(claimId, expectedVersion));

		store(claimId, expectedVersion, sentiment);

		return sentiment;
	}

	@Transactional(TxType.REQUIRES_NEW)
	String correspondenceUnderReview(long claimId, long expectedVersion) {
		var state = ReviewGuard.openReview(claimId, expectedVersion, LockModeType.NONE);

		return CorrespondenceText.cap(state.claim.body, this.config.limits().maxBodyCharacters());
	}

	// Locked so a reply superseding the review, or a decision claiming it, can't commit between the check and the write
	@Transactional(TxType.REQUIRES_NEW)
	void store(long claimId, long expectedVersion, String sentiment) {
		ReviewGuard.openReview(claimId, expectedVersion, LockModeType.PESSIMISTIC_WRITE).claim.sentiment = sentiment;
	}

	private String assess(long claimId, String correspondence) {
		return Optional.ofNullable(callModel(claimId, correspondence))
			.map(String::strip)
			.filter(not(String::isEmpty))
			.orElseThrow(() -> new SentimentAssessmentFailedException(claimId, null));
	}

	private String callModel(long claimId, String correspondence) {
		try {
			return this.agent.assessClaimantSentiment(correspondence);
		}
		catch (RuntimeException e) {
			Log.warnf(e, "The sentiment of claim %d couldn't be assessed again", claimId);
			throw new SentimentAssessmentFailedException(claimId, e);
		}
	}
}
