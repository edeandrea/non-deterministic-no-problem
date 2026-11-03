package ai.scoring.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import jakarta.inject.Inject;

import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.Test;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

import ai.scoring.conversation.ConversationEvents;

@QuarkusTest
class ConversationEndedScorerTests {
	@InjectMock
	SessionScoringService sessionScoringService;

	@Inject
	ConversationEvents conversationEvents;

	@Test
	void anEndedConversationIsScoredOnce() {
		this.conversationEvents.ended("scored-conversation")
			.toCompletableFuture()
			.join();

		verify(this.sessionScoringService).scoreSession("scored-conversation");
		verifyNoMoreInteractions(this.sessionScoringService);
	}

	@Test
	void aFailingScorerIsLoggedNotLost() {
		doThrow(new IllegalStateException("the scorer broke"))
			.when(this.sessionScoringService)
			.scoreSession("broken-conversation");

		// The JBoss LogManager loggers are java.util.logging loggers, so a handler captures what ConversationEvents logs
		var records = new CopyOnWriteArrayList<LogRecord>();
		var logger = Logger.getLogger(ConversationEvents.class.getName());
		var handler = new CapturingHandler(records);
		logger.addHandler(handler);

		try {
			assertThatThrownBy(() -> this.conversationEvents.ended("broken-conversation").toCompletableFuture().join())
				.hasRootCauseMessage("the scorer broke");
		}
		finally {
			logger.removeHandler(handler);
		}

		assertThat(records)
			.singleElement()
			.satisfies(r -> assertThat(((ExtLogRecord) r).getFormattedMessage()).contains("broken-conversation"))
			.extracting(LogRecord::getThrown)
			.isNotNull();
	}

	private static final class CapturingHandler extends Handler {
		private final List<LogRecord> records;

		private CapturingHandler(List<LogRecord> records) {
			this.records = records;
		}

		@Override
		public void publish(LogRecord logRecord) {
			this.records.add(logRecord);
		}

		@Override
		public void flush() {
			// Nothing buffered
		}

		@Override
		public void close() {
			// Nothing to release
		}
	}
}
