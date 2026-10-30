package org.parasol.intake.reply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimCategory;
import org.parasol.claim.model.ClaimCorrespondence;
import org.parasol.claim.model.CorrespondenceDirection;
import org.parasol.intake.MissingItem;
import org.parasol.testing.StubbedModelsTestProfile;
import org.parasol.testing.mail.GreenMailMailbox;
import org.parasol.testing.mail.ReceivedEmail;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(StubbedModelsTestProfile.class)
class IntakeReplySenderTests {
	private static final Duration WAIT_DURATION = Duration.ofSeconds(30);

	@Inject
	IntakeReplySender sender;

	@Inject
	GreenMailMailbox mailbox;

	private final List<Long> claimIds = new java.util.ArrayList<>();

	@BeforeEach
	void purge() {
		this.mailbox.purge();
	}

	// The claims are committed, so the sender's own transaction can see them; nothing rolls them back
	@AfterEach
	void deleteClaims() {
		QuarkusTransaction.requiringNew().run(() -> this.claimIds.forEach(id -> Claim.deleteById(id)));
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
			"CLM01000000",
			new ThreadingHeaders(Optional.of(inReplyTo), references));

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
			ThreadingHeaders.replyingTo("<follow-up@example.com>"));

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
			"CLM01000000",
			ThreadingHeaders.none());

		assertThat(this.mailbox.awaitMessage(recipient, WAIT_DURATION))
			.returns("[CLM01000000] Re: Status update", ReceivedEmail::subject)
			.returns("Body text", ReceivedEmail::body)
			.returns(Optional.of("auto-replied"), email -> email.firstHeader("Auto-Submitted"))
			.returns(Optional.empty(), email -> email.firstHeader("In-Reply-To"))
			.returns(Optional.empty(), email -> email.firstHeader("References"));
	}

	@Test
	void aTemplateReplyAboutAClaimIsRecordedWithTheMessageIdItWasSentWith() {
		var recipient = "jane.doe@example.com";
		var claimNumber = createClaim();

		this.sender.sendTemplateReply(IntakeTemplates.claimInProcess(Optional.of("Jane Doe"), claimNumber), recipient, "My claim", claimNumber, ThreadingHeaders.none());

		var received = this.mailbox.awaitMessage(recipient, WAIT_DURATION);
		var recorded = recorded(claimNumber);

		assertThat(recorded)
			.singleElement()
			.returns(received.firstHeader("Message-ID").orElseThrow(), row -> row.messageId)
			.returns(CorrespondenceDirection.OUTBOUND, row -> row.direction)
			.returns("claims@parasol.com", row -> row.sender)
			.returns("[%s] Re: My claim".formatted(claimNumber), row -> row.subject)
			.satisfies(row -> assertThat(row.strippedText)
				.contains("Dear Jane Doe,", "Your claim %s has been processed".formatted(claimNumber))
				.doesNotContain("<"))
			.satisfies(row -> assertThat(row.receivedAt).isEqualTo(row.sentDate));
		assertThat(received.firstHeader("Message-ID").orElseThrow())
			.matches("<[0-9a-f-]{36}@parasol\\.com>");
	}

	@Test
	void aTextReplyAboutAClaimIsRecorded() {
		var recipient = "jane.doe@example.com";
		var claimNumber = createClaim();

		this.sender.sendTextReply(recipient, "Re: Status", "Your claim is in review.", claimNumber, ThreadingHeaders.none());

		var received = this.mailbox.awaitMessage(recipient, WAIT_DURATION);

		assertThat(recorded(claimNumber))
			.singleElement()
			.returns(received.firstHeader("Message-ID").orElseThrow(), row -> row.messageId)
			.returns("[%s] Re: Status".formatted(claimNumber), row -> row.subject)
			.returns("Your claim is in review.", row -> row.strippedText);
	}

	// A reply that mustn't disclose a claim has none to be recorded against, but still gets its own Message-ID
	@Test
	void aReplyWithoutAClaimRecordsNothingButHasAMessageId() {
		var recipient = "jane.doe@example.com";
		var before = QuarkusTransaction.requiringNew().call(() -> ClaimCorrespondence.count());

		this.sender.sendTemplateReply(IntakeTemplates.noMatchingClaim(Optional.empty()), recipient, "My claim", ThreadingHeaders.none());
		this.sender.sendTextReply(recipient, "My claim", "Body", ThreadingHeaders.none());

		assertThat(this.mailbox.awaitMessage(recipient, WAIT_DURATION).firstHeader("Message-ID"))
			.get(STRING)
			.matches("<[0-9a-f-]{36}@parasol\\.com>");
		assertThat(QuarkusTransaction.requiringNew().call(() -> ClaimCorrespondence.count()))
			.isEqualTo(before);
	}

	private String createClaim() {
		var claim = QuarkusTransaction.requiringNew().call(() -> {
			var created = new Claim();
			created.category = ClaimCategory.OTHER;
			created.inceptionDate = LocalDate.of(2020, 1, 1);
			created.incidentDate = LocalDate.of(2024, 1, 1);
			created.persistAndFlush();

			return created;
		});

		this.claimIds.add(claim.id);

		return claim.claimNumber;
	}

	private static List<ClaimCorrespondence> recorded(String claimNumber) {
		return QuarkusTransaction.requiringNew().call(() ->
			ClaimCorrespondence.list("claim.claimNumber", claimNumber));
	}
}
