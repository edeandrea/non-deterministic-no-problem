package org.parasol.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Optional;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.parasol.model.claim.Claim;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

import io.quarkiverse.mailpit.test.InjectMailbox;
import io.quarkiverse.mailpit.test.Mailbox;
import io.quarkiverse.mailpit.test.WithMailbox;
import io.quarkiverse.mailpit.test.model.Message;

@QuarkusTest
@WithMailbox
class NotificationServiceTests {
	private static final Duration WAIT_DURATION = Duration.ofMinutes(5);

	@InjectMailbox
	Mailbox mailbox;

	@Inject
	NotificationService emailService;

	@AfterEach
	void afterEach() {
		this.mailbox.clear();
	}

	@Test
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

			await()
				.atMost(WAIT_DURATION)
				.until(() -> findFirstMessage().isPresent());

			// Find the message in Mailpit
			var message = findFirstMessage();

			// Assert the message has the correct subject & email address
			assertThat(message)
				.isNotNull()
				.get()
				.extracting(m -> m.getTo().getFirst().getAddress())
				.isEqualTo(claim.emailAddress);

			// Assert that the message has the correct info in the body
			assertThat(message.get().getText().strip())
				.isNotNull();

			assertThat(message.get().getText().strip().replaceAll("\r\n", "\n"))
				.isNotNull()
				.startsWith(GenerateEmailService.EMAIL_STARTING.strip())
				.endsWith(GenerateEmailService.EMAIL_ENDING.strip())
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

	private void assertNoEmailSent() {
		assertThat(findFirstMessage())
			.isNotNull()
			.isNotPresent();
	}

	private Optional<Message> findFirstMessage() {
		return Optional.ofNullable(this.mailbox.findFirst(NotificationService.MESSAGE_FROM));
	}
}