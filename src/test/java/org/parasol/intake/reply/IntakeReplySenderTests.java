package org.parasol.intake.reply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.parasol.intake.IntakeTestProfile;
import org.parasol.intake.MissingItem;
import org.parasol.testing.mail.GreenMailMailbox;
import org.parasol.testing.mail.ReceivedEmail;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(IntakeTestProfile.class)
class IntakeReplySenderTests {
	private static final Duration WAIT_DURATION = Duration.ofSeconds(30);

	@Inject
	IntakeReplySender sender;

	@Inject
	GreenMailMailbox mailbox;

	@BeforeEach
	void purge() {
		this.mailbox.purge();
	}

	@Test
	void templateReplyAddsThreadingHeadersAndClaimPrefix() {
		var recipient = "jane.doe@example.com";
		var inReplyTo = "<reply-1@parasol.com>";
		var references = List.of("<root@parasol.com>", inReplyTo);

		this.sender.sendTemplateReply(
			IntakeTemplates.missingInformation(
				Optional.of("Jane Doe"),
				"CLM01000000",
				List.of(MissingItem.CATEGORY, MissingItem.LOCATION),
				true,
				List.of()),
			recipient,
			"Need more information",
			Optional.of("CLM01000000"),
			Optional.of(inReplyTo),
			references);

		assertThat(this.mailbox.awaitMessage(recipient, WAIT_DURATION))
			.returns("claims@parasol.com", ReceivedEmail::from)
			.returns("[CLM01000000] Re: Need more information", ReceivedEmail::subject)
			.returns(Optional.of("auto-replied"), email -> email.firstHeader("Auto-Submitted"))
			.returns(Optional.of(inReplyTo), email -> email.firstHeader("In-Reply-To"))
			.returns(Optional.of("<root@parasol.com> " + inReplyTo), email -> email.firstHeader("References"))
			.extracting(ReceivedEmail::body, STRING)
			.contains("Dear Jane Doe,", "claim CLM01000000", "- category", "- location", "Parasol Insurance Claims Department", "1-800-CAR-SAFE");
	}

	@Test
	void templateReplyWithoutClaimHasNoClaimPrefix() {
		var recipient = "jane.doe@example.com";

		this.sender.sendTemplateReply(
			IntakeTemplates.noMatchingClaim(Optional.empty()),
			recipient,
			"RE: My claim",
			Optional.empty(),
			Optional.of("<follow-up@example.com>"),
			List.of());

		assertThat(this.mailbox.awaitMessage(recipient, WAIT_DURATION))
			.returns("Re: My claim", ReceivedEmail::subject)
			.returns(Optional.of("<follow-up@example.com>"), email -> email.firstHeader("References"));
	}

	@Test
	void textReplyWithoutInboundMessageIdOmitsThreadingHeaders() {
		var recipient = "jane.doe@example.com";

		this.sender.sendTextReply(
			recipient,
			"Status update",
			"Body text",
			Optional.of("CLM01000000"),
			Optional.empty(),
			List.of());

		assertThat(this.mailbox.awaitMessage(recipient, WAIT_DURATION))
			.returns("[CLM01000000] Re: Status update", ReceivedEmail::subject)
			.returns("Body text", ReceivedEmail::body)
			.returns(Optional.of("auto-replied"), email -> email.firstHeader("Auto-Submitted"))
			.returns(Optional.empty(), email -> email.firstHeader("In-Reply-To"))
			.returns(Optional.empty(), email -> email.firstHeader("References"));
	}
}
