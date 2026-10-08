package org.parasol.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import java.time.Duration;
import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.parasol.claim.model.Claim;
import org.parasol.notification.ai.GenerateEmailService;
import org.parasol.notification.guardrail.EmailEndsAppropriatelyOutputGuardrail;
import org.parasol.testing.mail.GreenMailMailbox;
import org.parasol.testing.mail.ReceivedEmail;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class NotificationServiceTests {
	private static final Duration WAIT_DURATION = Duration.ofMinutes(5);

	@Inject
	GreenMailMailbox mailbox;

	@Inject
	NotificationService emailService;

	@Inject
	EmailEndsAppropriatelyOutputGuardrail endsAppropriately;

	// Before rather than after each test, so mail left over by other test classes can't leak into the assertions
	@BeforeEach
	void purgeMail() {
		this.mailbox.purge();
	}

	@Test
	@DisabledIfSystemProperty(
		named = "quarkus.test.profile",
		matches = ".*ollama-openai.*",
		disabledReason = """
			Needs a model that reproduces the rendered GenerateEmailService.EMAIL_ENDING verbatim, or \
			EmailEndsAppropriatelyOutputGuardrail reprompts until max-retries. %ollama gives generate-email qwen3:4b \
			with thinking off, which does reproduce it. %ollama-openai can't: it reaches Ollama through the \
			OpenAI-compatible endpoint, where model-options.think isn't available, and qwen3:4b spends ~6k reasoning \
			tokens per email - minutes on a CI runner. It keeps parasol-chat's granite4:micro, which reflows the \
			closing block (so do llama3.2, ministral-3:3b and qwen2.5:3b). Runs everywhere else, including the \
			default profile against gpt-5-mini. This is NOT #228, which is fixed - see EmailOutputGuardrailChainTests."""
	)
	void emailSendsWhenUserExists() {
		QuarkusTransaction.begin(QuarkusTransaction.beginOptions().timeout((int) WAIT_DURATION.toSeconds()));

		try {
			var status = "Denied";
			var claimId = 1L;
			var claim = Claim.<Claim>findByIdOptional(claimId)
			                 .orElseThrow(() -> new IllegalArgumentException("Marty McFly's claim should be found!"));

			assertThat(this.emailService.updateClaimStatus(claimId, status))
				.isNotNull()
				.isEqualTo(NotificationService.NOTIFICATION_SUCCESS, claim.emailAddress, claim.claimNumber, status);

			// Read it from the claimant's GreenMail INBOX
			assertThat(this.mailbox.awaitMessage(claim.emailAddress, WAIT_DURATION))
				.returns(NotificationService.MESSAGE_FROM, ReceivedEmail::from)
				.returns(List.of(claim.emailAddress), ReceivedEmail::to)
				.extracting(message -> message.body().strip().replace("\r\n", "\n"), STRING)
				.startsWith(GenerateEmailService.EMAIL_STARTING.strip())
				.endsWith(this.endsAppropriately.expectedEnding().strip())
				.contains(claim.clientName)
				.containsIgnoringCase(claim.claimNumber)
				.containsIgnoringCase(status);

			// Assert that the claim status was updated in the database
			var updatedClaim = Claim.findById(claimId);
			assertThat(updatedClaim)
				.isNotNull()
				.extracting("status")
				.isEqualTo(status);
		}
		finally {
			QuarkusTransaction.rollback();
		}
	}

	@Test
	@TestTransaction
	void noEmailSentWhenClaimantNotFound() {
		assertThat(this.emailService.updateClaimStatus(-1L, "Under investigation"))
			.isNotNull()
			.isEqualTo(NotificationService.NOTIFICATION_NO_CLAIMANT_FOUND);

		assertNoEmailSent();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "a", "aa", " ", "  " })
	void invalidStatus(String status) {
		assertThat(this.emailService.updateClaimStatus(1L, status))
			.isNotNull()
			.isEqualTo(NotificationService.INVALID_STATUS, status);

		assertNoEmailSent();
	}

	// The send is synchronous (NotificationService awaits the mailer), so any email would already be in GreenMail
	private void assertNoEmailSent() {
		assertThat(this.mailbox.allMessages())
			.isEmpty();
	}
}