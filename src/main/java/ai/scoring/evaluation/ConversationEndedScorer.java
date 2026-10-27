package ai.scoring.evaluation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;

import io.quarkus.logging.Log;

import ai.scoring.conversation.ConversationEndedEvent;

/**
 * Scores a conversation's session when it ends. The observer is asynchronous, so it runs on a worker thread and the
 * scorer may block; a failure is logged by {@link ai.scoring.conversation.ConversationEvents ConversationEvents}.
 */
@ApplicationScoped
class ConversationEndedScorer {
	private final SessionScoringService sessionScoringService;

	ConversationEndedScorer(SessionScoringService sessionScoringService) {
		this.sessionScoringService = sessionScoringService;
	}

	void scoreSession(@ObservesAsync ConversationEndedEvent event) {
		Log.debugf("Scoring the session of conversation %s", event.conversationId());
		this.sessionScoringService.scoreSession(event.conversationId());
	}
}
