package org.parasol.intake.mailbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class MailLoopGuardTests {
	private static final String INTAKE_ADDRESS = "claims@parasol.com";

	@Test
	void anAutoReplyIsIgnored() {
		assertThat(MailLoopGuard.shouldIgnore(email("jane@example.com", true), INTAKE_ADDRESS)).isTrue();
	}

	@Test
	void mailFromTheIntakeAddressIsIgnoredWhateverTheCase() {
		assertThat(MailLoopGuard.shouldIgnore(email("Claims@Parasol.com", false), INTAKE_ADDRESS)).isTrue();
	}

	@Test
	void anOrdinaryEmailIsNotIgnored() {
		assertThat(MailLoopGuard.shouldIgnore(email("jane@example.com", false), INTAKE_ADDRESS)).isFalse();
	}

	private static InboundEmail email(String from, boolean isAutoReply) {
		return new InboundEmail(
			Optional.of("<id@example.com>"),
			List.of(),
			Optional.of(from),
			Optional.empty(),
			"Subject",
			Instant.EPOCH,
			"Body",
			"Body",
			List.of(),
			List.of(),
			isAutoReply
		);
	}
}
