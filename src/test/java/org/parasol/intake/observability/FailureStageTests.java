package org.parasol.intake.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.parasol.intake.mailbox.MailboxException;
import org.parasol.intake.mailbox.MailboxMoveException;
import org.parasol.intake.reply.ReplyFailedException;

import dev.langchain4j.exception.LangChain4jException;
import jakarta.mail.MessagingException;
import jakarta.persistence.PersistenceException;

/**
 * Pins the packages each stage is recognized by: a moved or renamed exception class would otherwise turn into
 * {@code unknown} without anything failing.
 */
class FailureStageTests {
	@Test
	void mailboxFailuresAreFetch() {
		// Mocked, since the exception's constructor is package-private; the mock lives in the same package
		assertThat(FailureStage.of(mock(MailboxException.class))).isEqualTo(FailureStage.FETCH);
	}

	@Test
	void aFailedMailboxChangeIsMoveNotFetch() {
		assertThat(FailureStage.of(mock(MailboxMoveException.class))).isEqualTo(FailureStage.MOVE);
	}

	@Test
	void aFailedReplyIsReplyWhateverTheMailerThrew() {
		assertThat(FailureStage.of(mock(ReplyFailedException.class))).isEqualTo(FailureStage.REPLY);
	}

	@Test
	void agentFailuresAreAgent() {
		assertThat(FailureStage.of(new LangChain4jException("x"))).isEqualTo(FailureStage.AGENT);
	}

	@Test
	void persistenceFailuresArePersist() {
		assertThat(FailureStage.of(new PersistenceException("x"))).isEqualTo(FailureStage.PERSIST);
	}

	@Test
	void mailFailuresAreReply() {
		assertThat(FailureStage.of(new MessagingException("x"))).isEqualTo(FailureStage.REPLY);
	}

	@Test
	void theFirstKnownExceptionInTheCauseChainWins() {
		assertThat(FailureStage.of(new IllegalStateException("x", new PersistenceException("y")))).isEqualTo(FailureStage.PERSIST);
	}

	@Test
	void anythingElseIsUnknown() {
		assertThat(FailureStage.of(new IOException("x"))).isEqualTo(FailureStage.UNKNOWN);
	}
}
