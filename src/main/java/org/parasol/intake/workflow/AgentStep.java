package org.parasol.intake.workflow;

import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional.TxType;
import jakarta.transaction.Transactional;

import org.parasol.claim.model.Claim;
import org.parasol.intake.config.IntakeConfig;
import org.parasol.intake.MissingItem;
import org.parasol.intake.agent.ClaimsMailboxAgent;
import org.parasol.intake.agent.triage.ClaimStatusTools;
import org.parasol.intake.mailbox.InboundEmail;
import org.parasol.intake.model.ClaimIntakeState;
import org.parasol.intake.model.IncidentDetails;
import org.parasol.intake.model.MatchedClaim;
import org.parasol.intake.observability.IntakeActivities;
import org.parasol.intake.observability.IntakeActivity.EmailClassified;
import org.parasol.intake.observability.MatchedClaimKind;
import org.parasol.intake.workflow.mail.IntakeEmailReader;
import org.parasol.intake.workflow.outcome.ClaimFields;


/**
 * Runs the agentic root as one step. The agents only decide; nothing is persisted or sent here, and no transaction is
 * open while they work.
 */
@ApplicationScoped
class AgentStep {
	private final IntakeEmailReader emails;
	private final ClaimsMailboxAgent agent;
	private final IntakeConfig config;
	private final IntakeActivities activities;

	AgentStep(IntakeEmailReader emails, ClaimsMailboxAgent agent, IntakeConfig config, IntakeActivities activities) {
		this.emails = emails;
		this.agent = agent;
		this.config = config;
		this.activities = activities;
	}

	IntakeState run(IntakeState state) {
		var email = this.emails.require(state.messageId());
		var history = snapshot(state, email);
		var matched = history.matchedClaim();
		var classified = this.agent.process(
			CorrespondenceText.cap(history.correspondence(), this.config.limits().maxBodyCharacters()),
			matched,
			history.extractedSoFar(),
			history.requestedItems(),
			email.sentDate().atZone(ZoneOffset.UTC).toLocalDate().toString(),
			ClaimStatusTools.scopedTo(matched));

		this.activities.publish(new EmailClassified(classified.emailType(), MatchedClaimKind.of(matched)));

		return state.withOutcome(classified.outcome());
	}

	// Its own transaction, committed before the agents run: no transaction is held across an LLM call
	@Transactional(TxType.REQUIRES_NEW)
	History snapshot(IntakeState state, InboundEmail email) {
		return Optional.ofNullable(state.claimId())
			.map(id -> Claim.<Claim>findById(id))
			.map(claim -> new History(
				MatchedClaim.of(claim.claimNumber, claim.status),
				CorrespondenceText.appendReply(claim.body, email.sentDate().atZone(ZoneOffset.UTC).toLocalDate(), email.strippedBody()),
				ClaimFields.extractedSoFar(claim),
				ClaimIntakeState.findForClaim(claim.id).map(intake -> Set.copyOf(intake.requestedItems)).orElse(Set.of())))
			.orElseGet(() -> new History(
				MatchedClaim.none(),
				CorrespondenceText.firstEmail(email.subject(), email.strippedBody()),
				IncidentDetails.empty(),
				Set.of()));
	}

	record History(MatchedClaim matchedClaim, String correspondence, IncidentDetails extractedSoFar, Set<MissingItem> requestedItems) {
	}
}
