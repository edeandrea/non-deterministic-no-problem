package org.ericoleg.ndnp.ai;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.ericoleg.ndnp.ai.GenerateEmailService.ClaimInfo;
import org.ericoleg.ndnp.model.Claim;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.quarkus.narayana.jta.QuarkusTransaction;

import dev.langchain4j.agent.tool.Tool;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;

@ApplicationScoped
public class NotificationService {
	// Invalid status to set
	static final String INVALID_STATUS = "Status \"%s\" is not a valid status";

	// Respond to the AI with success
	static final String NOTIFICATION_SUCCESS = "%s (claim number %s) has been notified of status update \"%s\"";

	// Respond to the AI with the fact that we couldn't find a claim record for some reason (shouldn't ever happen, but who knows...)
	static final String NOTIFICATION_NO_CLAIMANT_FOUND = "No claim record found in the database for the given claim";

	// Who the email is from
	static final String MESSAGE_FROM = "noreply@parasol.com";

	// Email subject
	static final String MESSAGE_SUBJECT = "Update to your claim";

	@Inject
	ReactiveMailer mailer;

	@Inject
	GenerateEmailService generateEmailService;

	@Tool("""
		Update the status of a claim.
		This should only be used if the user explicitly asks to update the status of a claim.
		Do not decide on your own to update the status.
		""")
	@WithSpan("NotificationService.updateClaimStatus")
	public String updateClaimStatus(@SpanAttribute("arg.claimId") long claimId, @SpanAttribute("arg.status") String status) {
		// Only want to actually do anything if the passed in status has at least 3 characters
		return Optional.ofNullable(status)
			.filter(s -> s.trim().length() > 2)
			.map(s -> updateStatus(claimId, s))
			.orElse(INVALID_STATUS.formatted(status));
	}

	private String updateStatus(long claimId, String status) {
		// Only want to actually do anything if there is a corresponding claim in the database for the given claimId
		return QuarkusTransaction.requiringNew().call(() ->
			Claim.<Claim>findByIdOptional(claimId)
			     .map(claim -> updateStatus(claim, status))
			)
			.map(this::sendEmail)
			.orElse(NOTIFICATION_NO_CLAIMANT_FOUND);
	}

	private Claim updateStatus(Claim claim, String status) {
		// Capitalize the first letter
		claim.status = status.trim().substring(0, 1).toUpperCase() + status.trim().substring(1);

		// Save the claim with updated status
		Claim.persist(claim);

		return claim;
	}

	private String sendEmail(Claim claim) {
		// Create the email
		var email = Mail.withText(
			claim.emailAddress,
				MESSAGE_SUBJECT,
				this.generateEmailService.generateEmail(new ClaimInfo(claim.clientName, claim.claimNumber, claim.status))
			)
			.setFrom(MESSAGE_FROM);

		// Send the email to the user
		// Need to move this to another thread because the mailer blocks the...which causes deadlock
		// Fail if it doesn't finish in 15 seconds
		this.mailer.send(email)
			.runSubscriptionOn(ForkJoinPool.commonPool())
			.await().atMost(Duration.ofSeconds(15));

		// Return a note to the AI
		return NOTIFICATION_SUCCESS.formatted(claim.emailAddress, claim.claimNumber, claim.status);
	}
}
