package org.parasol.testing.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.test.junit.QuarkusTest;

// Sends real mail through the app's mailer to GreenMail and reads it back over IMAP: no LLM involved
@QuarkusTest
class GreenMailMailboxTests {
	private static final Duration WAIT_DURATION = Duration.ofSeconds(30);
	private static final String SENDER = "sender@parasol.com";
	private static final String RECIPIENT = "recipient@example.com";
	private static final String OTHER_USER = "someone.else@example.com";
	private static final String SUBJECT = "Mailbox isolation";
	private static final String BODY = "Only the recipient should see this";

	@Inject
	Mailer mailer;

	@Inject
	GreenMailMailbox mailbox;

	@BeforeEach
	void purgeMail() {
		this.mailbox.purge();
	}

	@Test
	void messageReachesOnlyItsRecipient() {
		send(RECIPIENT);

		assertThat(this.mailbox.awaitMessage(RECIPIENT, WAIT_DURATION))
			.returns(SENDER, ReceivedEmail::from)
			.returns(List.of(RECIPIENT), ReceivedEmail::to)
			.returns(SUBJECT, ReceivedEmail::subject)
			.extracting(message -> message.body().strip())
			.isEqualTo(BODY);

		assertThat(this.mailbox.messages(OTHER_USER))
			.isEmpty();
	}

	@Test
	void purgeEmptiesEveryMailbox() {
		send(RECIPIENT);
		send(OTHER_USER);
		this.mailbox.awaitMessage(RECIPIENT, WAIT_DURATION);
		this.mailbox.awaitMessage(OTHER_USER, WAIT_DURATION);

		// Proves allMessages() sees every mailbox, which NotificationServiceTests relies on to show that no email was sent
		assertThat(this.mailbox.allMessages())
			.extracting(ReceivedEmail::to)
			.containsExactlyInAnyOrder(List.of(RECIPIENT), List.of(OTHER_USER));

		this.mailbox.purge();

		assertThat(this.mailbox.allMessages())
			.isEmpty();
	}

	private void send(String to) {
		this.mailer.send(
			Mail.withText(to, SUBJECT, BODY)
				.setFrom(SENDER)
		);
	}
}