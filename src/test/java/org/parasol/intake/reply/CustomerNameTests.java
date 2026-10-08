package org.parasol.intake.reply;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.parasol.claim.model.Claim;
import org.parasol.intake.mailbox.InboundEmail;

class CustomerNameTests {
	private static final String CLAIM_ADDRESS = "marty.mcfly@email.com";

	@Test
	void resolvedClaimFromItsOwnAddressUsesTheClientName() {
		assertThat(CustomerName.forReply(email(Optional.of("MARTY.McFly@email.com"), Optional.of("Doc")), Optional.of(claim("Marty McFly"))))
			.contains("Marty McFly");
	}

	@Test
	void resolvedClaimFromAnotherAddressUsesTheFromNameNotTheClientName() {
		assertThat(CustomerName.forReply(email(Optional.of("biff@email.com"), Optional.of("Biff Tannen")), Optional.of(claim("Marty McFly"))))
			.contains("Biff Tannen");
	}

	@Test
	void resolvedClaimFromAnotherAddressWithoutFromNameHasNoName() {
		assertThat(CustomerName.forReply(email(Optional.of("biff@email.com"), Optional.empty()), Optional.of(claim("Marty McFly"))))
			.isEmpty();
	}

	@Test
	void resolvedClaimWithoutClientNameFallsBackToTheFromName() {
		assertThat(CustomerName.forReply(email(Optional.of(CLAIM_ADDRESS), Optional.of("Marty")), Optional.of(claim("  "))))
			.contains("Marty");
	}

	@Test
	void noClaimUsesTheFromName() {
		assertThat(CustomerName.forReply(email(Optional.of(CLAIM_ADDRESS), Optional.of("  Marty McFly ")), Optional.empty()))
			.contains("Marty McFly");
	}

	@Test
	void emailWithoutFromAddressNeverUsesTheClientName() {
		assertThat(CustomerName.forReply(email(Optional.empty(), Optional.of("Marty")), Optional.of(claim("Marty McFly"))))
			.contains("Marty");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   ", "marty.mcfly@email.com" })
	void unusableFromNameHasNoName(String fromName) {
		assertThat(CustomerName.forReply(email(Optional.of(CLAIM_ADDRESS), Optional.of(fromName)), Optional.empty()))
			.isEmpty();
	}

	private static InboundEmail email(Optional<String> fromAddress, Optional<String> fromName) {
		return new InboundEmail(
			Optional.of("<message@example.com>"),
			List.of(),
			fromAddress,
			fromName,
			"Subject",
			Instant.EPOCH,
			"Body",
			"Body",
			List.of(),
			List.of(),
			false);
	}

	private static Claim claim(String clientName) {
		var claim = new Claim();
		claim.clientName = clientName;
		claim.emailAddress = CLAIM_ADDRESS;

		return claim;
	}
}
