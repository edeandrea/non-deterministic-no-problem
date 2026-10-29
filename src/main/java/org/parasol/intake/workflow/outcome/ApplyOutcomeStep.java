package org.parasol.intake.workflow.outcome;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional.TxType;
import jakarta.transaction.Transactional;

import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimCorrespondence;
import org.parasol.claim.model.ClaimImage;
import org.parasol.claim.model.ClaimImageKind;
import org.parasol.claim.model.IntakeClaimStatus;
import org.parasol.intake.MissingItem;
import org.parasol.intake.agent.extraction.ClaimExtractionRules;
import org.parasol.intake.agent.extraction.IsoValues;
import org.parasol.intake.mailbox.InboundEmail;
import org.parasol.intake.model.ClaimExtraction;
import org.parasol.intake.model.ClaimIntakeState;
import org.parasol.intake.model.IntakeOutcome.NewClaim;
import org.parasol.intake.model.IntakeOutcome.NoMatchingClaim;
import org.parasol.intake.model.IntakeOutcome.NotAClaim;
import org.parasol.intake.model.IntakeOutcome.PendingClaimUpdate;
import org.parasol.intake.model.IntakeOutcome.StatusReply;
import org.parasol.intake.observability.IntakeActivities;
import org.parasol.intake.observability.IntakeActivity.ClaimStatusChanged;
import org.parasol.intake.workflow.CorrespondenceText;
import org.parasol.intake.workflow.IntakeState;
import org.parasol.intake.workflow.mail.IntakeEmailReader;


/**
 * Persists what the agents decided: a new claim with its photos, or a reply merged into a pending claim. Every other
 * outcome changes nothing, except that the email is recorded against the claim it matched. One transaction, and the claim
 * is never touched outside it.
 */
@ApplicationScoped
public class ApplyOutcomeStep {
	private static final Pattern ADDRESS_LIKE = Pattern.compile("\\S+@\\S+");

	private final IntakeEmailReader emails;
	private final IntakeActivities activities;

	ApplyOutcomeStep(IntakeEmailReader emails, IntakeActivities activities) {
		this.emails = emails;
		this.activities = activities;
	}

	public IntakeState apply(IntakeState state, String runId, String conversationId) {
		var applied = persist(state, this.emails.require(state.messageId()), runId, conversationId);

		if ((applied.outcome() instanceof NewClaim) || applied.outcome() instanceof PendingClaimUpdate) {
			this.activities.publish(new ClaimStatusChanged(statusFor(applied.needsReview())));
		}

		return applied;
	}

	// One transaction for the claim, its images and its correspondence; the IMAP read above stays outside it
	@Transactional(TxType.REQUIRES_NEW)
	IntakeState persist(IntakeState state, InboundEmail email, String runId, String conversationId) {
		return switch (state.outcome()) {
			case NewClaim newClaim -> applyNewClaim(state, email, newClaim.extraction(), runId, conversationId);
			case PendingClaimUpdate update -> applyUpdate(state, email, update.extraction(), runId);
			case StatusReply ignored -> linkToMatchedClaim(state);
			case NotAClaim ignored -> state;
			case NoMatchingClaim ignored -> state;
		};
	}

	private IntakeState applyNewClaim(IntakeState state, InboundEmail email, ClaimExtraction extraction, String runId, String conversationId) {
		var missing = ClaimExtractionRules.findMissingItems(extraction, Set.of(), false);
		var isComplete = missing.isEmpty();
		var claim = new Claim();
		var details = extraction.details();

		claim.clientName = email.fromName()
			.filter(name -> !ADDRESS_LIKE.matcher(name).matches())
			.or(email::fromAddress)
			.orElse(null);
		claim.emailAddress = email.fromAddress().orElse(null);
		claim.subject = email.subject();
		claim.body = email.body();
		claim.policyNumber = state.policyNumber();
		claim.inceptionDate = InceptionDates.before(IsoValues.parseDate(details.incidentDate()).orElseGet(LocalDate::now), ThreadLocalRandom.current());
		ClaimFields.merge(claim, extraction);
		claim.status = statusFor(isComplete).label();
		claim.persistAndFlush();

		var intake = ClaimIntakeState.createFor(claim, conversationId);
		intake.policyNumberGenerated = state.policyNumberGenerated();
		setReviewState(intake, isComplete, missing, runId);

		storeImages(claim, email);
		linkCorrespondence(email, claim);

		return state.withApplied(claim.id, missing, isComplete);
	}

	private IntakeState applyUpdate(IntakeState state, InboundEmail email, ClaimExtraction extraction, String runId) {
		var claim = Claim.<Claim>findById(state.claimId());
		var replyIsBlank = email.strippedBody().isBlank();
		var intake = ClaimIntakeState.forClaim(claim);
		var missing = ClaimExtractionRules.findMissingItems(extraction, intake.requestedItems, replyIsBlank);
		var isComplete = missing.isEmpty();

		claim.body = CorrespondenceText.appendReply(claim.body, email.sentDate().atZone(ZoneOffset.UTC).toLocalDate(), email.strippedBody());
		ClaimFields.merge(claim, extraction);

		if (state.policyNumber() != null) {
			claim.policyNumber = state.policyNumber();
			intake.policyNumberGenerated = state.policyNumberGenerated();
		}

		claim.status = statusFor(isComplete).label();
		setReviewState(intake, isComplete, missing, runId);
		storeImages(claim, email);
		linkCorrespondence(email, claim);
		Claim.flush();

		return state.withApplied(claim.id, missing, isComplete);
	}

	private IntakeState linkToMatchedClaim(IntakeState state) {
		Optional.ofNullable(state.claimId())
			.map(id -> Claim.<Claim>findById(id))
			.ifPresent(claim -> ClaimCorrespondence.findByMessageId(state.messageId()).ifPresent(correspondence -> correspondence.claim = claim));

		return state;
	}

	private static IntakeClaimStatus statusFor(boolean isComplete) {
		return isComplete ? IntakeClaimStatus.PENDING_REVIEW : IntakeClaimStatus.PENDING_INFORMATION;
	}

	private static void setReviewState(ClaimIntakeState intake, boolean isComplete, Set<MissingItem> missing, String runId) {
		intake.reviewRunId = isComplete ? runId : null;
		intake.reviewOpenedAt = isComplete ? Instant.now() : null;
		intake.requestedItems = isComplete ? new HashSet<>() : new HashSet<>(missing);
	}

	private static void storeImages(Claim claim, InboundEmail email) {
		email.images()
			.forEach(image -> ClaimImage.store(claim, ClaimImageKind.ORIGINAL, image.fileName(), image.contentType(), image.data()));
	}

	private static void linkCorrespondence(InboundEmail email, Claim claim) {
		email.messageId()
			.flatMap(ClaimCorrespondence::findByMessageId)
			.ifPresent(correspondence -> correspondence.claim = claim);
	}
}
