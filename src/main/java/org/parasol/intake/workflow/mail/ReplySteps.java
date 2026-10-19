package org.parasol.intake.workflow.mail;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional.TxType;
import jakarta.transaction.Transactional;

import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimImage;
import org.parasol.intake.mailbox.InboundEmail;
import org.parasol.intake.model.IntakeOutcome.NewClaim;
import org.parasol.intake.model.IntakeOutcome.NoMatchingClaim;
import org.parasol.intake.model.IntakeOutcome.NotAClaim;
import org.parasol.intake.model.IntakeOutcome.PendingClaimUpdate;
import org.parasol.intake.model.IntakeOutcome.StatusReply;
import org.parasol.intake.reply.CustomerName;
import org.parasol.intake.reply.IntakeReplySender;
import org.parasol.intake.reply.IntakeTemplates;
import org.parasol.intake.reply.ThreadingHeaders;
import org.parasol.intake.workflow.IntakeState;

import io.quarkus.mailer.MailTemplate.MailTemplateInstance;

/**
 * The workflow's reply steps ({@code reply}, {@code replyNoMatchingClaim}, {@code replyWhichClaim},
 * {@code replyPolicyInconsistent}): every reply the run sends before the review. Each blocks until the mail server accepts
 * it, so a step that replies has answered when it returns.
 */
@ApplicationScoped
public class ReplySteps {
	private final IntakeEmailReader emails;
	private final IntakeReplySender sender;

	ReplySteps(IntakeEmailReader emails, IntakeReplySender sender) {
		this.emails = emails;
		this.sender = sender;
	}

	/**
	 * The reply for what the agents decided and the claim now looks like. No thank-you: that's the reviewer's.
	 */
	public IntakeState reply(IntakeState state) {
		var email = this.emails.require(state.messageId());
		var claim = Optional.ofNullable(state.claimId()).flatMap(this::claimView);
		var name = CustomerName.forReply(email, claim.map(ClaimView::asClaim));

		switch (state.outcome()) {
			case NewClaim ignored -> sendClaimReply(state, email, claim.orElseThrow(), name, false);
			case PendingClaimUpdate ignored -> sendClaimReply(state, email, claim.orElseThrow(), name, true);
			case StatusReply statusReply -> send(email, claim.map(ClaimView::claimNumber), statusReply.statusAnswer());
			case NotAClaim ignored -> send(email, Optional.empty(), IntakeTemplates.notAClaim(CustomerName.forReply(email, Optional.empty())));
			case NoMatchingClaim ignored -> send(email, Optional.empty(), IntakeTemplates.noMatchingClaim(CustomerName.forReply(email, Optional.empty())));
		}

		return state;
	}

	/**
	 * Replies that the email matched no claim of the sender's. Discloses nothing about any claim.
	 */
	public IntakeState replyNoMatchingClaim(IntakeState state) {
		var email = this.emails.require(state.messageId());
		send(email, Optional.empty(), IntakeTemplates.noMatchingClaim(CustomerName.forReply(email, Optional.empty())));

		return state;
	}

	/**
	 * Asks which of the sender's pending claims the email is about.
	 */
	public IntakeState replyWhichClaim(IntakeState state) {
		var email = this.emails.require(state.messageId());
		send(email, Optional.empty(), IntakeTemplates.whichClaim(CustomerName.forReply(email, Optional.empty()), state.pendingClaimNumbers()));

		return state;
	}

	/**
	 * Replies that the policy details didn't match. Says nothing about what didn't.
	 */
	public IntakeState replyPolicyInconsistent(IntakeState state) {
		var email = this.emails.require(state.messageId());
		send(email, Optional.empty(), IntakeTemplates.policyInconsistency(CustomerName.forReply(email, Optional.empty())));

		return state;
	}

	@Transactional(TxType.REQUIRES_NEW)
	Optional<ClaimView> claimView(long claimId) {
		return Claim.<Claim>findByIdOptional(claimId)
			.map(found -> new ClaimView(found.claimNumber, found.clientName, found.emailAddress, found.summary, ClaimImage.count("claim", found) > 0));
	}

	private void sendClaimReply(IntakeState state, InboundEmail email, ClaimView claim, Optional<String> name, boolean isUpdate) {
		var skipped = email.skippedAttachments();
		var template = state.needsReview()
			? IntakeTemplates.receivedFinalReview(name, claim.claimNumber(), claim.summary(), state.wasPendingReview(), claim.hasPhotos(), skipped)
			: missingInformation(isUpdate, name, claim, state, skipped);

		send(email, Optional.of(claim.claimNumber()), template);
	}

	private static MailTemplateInstance missingInformation(
		boolean isUpdate,
		Optional<String> name,
		ClaimView claim,
		IntakeState state,
		List<org.parasol.intake.mailbox.SkippedAttachment> skipped) {

		return isUpdate
			? IntakeTemplates.stillMissingInformation(name, claim.claimNumber(), state.orderedMissingItems(), claim.hasPhotos(), skipped)
			: IntakeTemplates.missingInformation(name, claim.claimNumber(), state.orderedMissingItems(), claim.hasPhotos(), skipped);
	}

	private void send(InboundEmail email, Optional<String> claimNumber, MailTemplateInstance template) {
		var threadingHeaders = ThreadingHeaders.of(email);

		claimNumber.ifPresentOrElse(
			number -> this.sender.sendTemplateReply(template, recipient(email), email.subject(), number, threadingHeaders),
			() -> this.sender.sendTemplateReply(template, recipient(email), email.subject(), threadingHeaders));
	}

	private void send(InboundEmail email, Optional<String> claimNumber, String text) {
		var threadingHeaders = ThreadingHeaders.of(email);

		claimNumber.ifPresentOrElse(
			number -> this.sender.sendTextReply(recipient(email), email.subject(), text, number, threadingHeaders),
			() -> this.sender.sendTextReply(recipient(email), email.subject(), text, threadingHeaders));
	}

	private static String recipient(InboundEmail email) {
		return email.fromAddress()
			.orElseThrow(() -> new IllegalStateException("The email has no sender to reply to"));
	}

	record ClaimView(String claimNumber, String clientName, String emailAddress, String summary, boolean hasPhotos) {
		Claim asClaim() {
			var claim = new Claim();
			claim.clientName = this.clientName;
			claim.emailAddress = this.emailAddress;
			return claim;
		}
	}
}
