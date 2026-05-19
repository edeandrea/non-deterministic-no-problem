package ai.scoring.langfuse.session;

import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;

import org.eclipse.microprofile.rest.client.inject.RestClient;

import ai.scoring.conversation.ConversationCompletedEvent;
import ai.scoring.langfuse.rest.LangfuseApiClient;
import ai.scoring.langfuse.rest.LangfuseNotFoundException;
import ai.scoring.langfuse.rest.model.CreateScoreValue;
import ai.scoring.langfuse.rest.model.LegacyCreateScoreRequest;
import ai.scoring.langfuse.rest.model.ScoreDataType;
import ai.scoring.langfuse.rest.model.Trace;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;

import io.quarkus.logging.Log;

@ApplicationScoped
public class LangfuseSessionScoringService {
	private static final int OTEL_FLUSH_DELAY_SECONDS = 5;

	private final Tracer tracer;
	private final LangfuseApiClient langfuseApiClient;
	private final SessionSentimentService sessionSentimentService;

	public LangfuseSessionScoringService(Tracer tracer, @RestClient LangfuseApiClient langfuseApiClient, SessionSentimentService sessionSentimentService) {
		this.tracer = tracer;
		this.langfuseApiClient = langfuseApiClient;
		this.sessionSentimentService = sessionSentimentService;
	}

	public void onConversationCompleted(@ObservesAsync ConversationCompletedEvent event) {
		var conversationId = event.getConversationId();
		Log.infof("Conversation %s completed - scoring conversation", conversationId);

		try {
			TimeUnit.SECONDS.sleep(OTEL_FLUSH_DELAY_SECONDS);
		}
		catch (InterruptedException e) {
			// eat it
		}

		var span = this.tracer.spanBuilder("ComputeSessionScore")
		                      .setSpanKind(SpanKind.INTERNAL)
		                      .startSpan();

		try (var scope = span.makeCurrent()) {
			fetchAndScoreSession(conversationId);
		}
		finally {
			span.end();
		}
	}

	private void fetchAndScoreSession(String conversationId) {
		try {
			this.langfuseApiClient.sessionsGet(conversationId)
				.getTraces()
				.stream()
				.filter(trace -> (trace.getTimestamp() != null) && (trace.getInput() != null) && (trace.getOutput() != null))
				.sorted(Comparator.comparing(Trace::getTimestamp))
				.map(trace -> new ConversationExchange(String.valueOf(trace.getInput()), String.valueOf(trace.getOutput())))
				.collect(Collectors.collectingAndThen(
					Collectors.toUnmodifiableList(),
					exchanges -> exchanges.isEmpty() ?
					             Optional.<SessionSentiment>empty() :
					             Optional.ofNullable(this.sessionSentimentService.evaluate(exchanges))
				))
				.ifPresentOrElse(
					sentiment -> {
						Log.infof("Session %s sentiment: %s - %s", conversationId, sentiment.sentiment(), sentiment.reasoning());
						saveScore(conversationId, sentiment);
					},
					() -> Log.debugf("No sentiment for session %s", conversationId)
				);
		}
		catch (LangfuseNotFoundException e) {
			Log.debugf("Session %s not found in Langfuse, skipping scoring", conversationId);
		}
	}

	private void saveScore(String conversationId, SessionSentiment sentiment) {
		var request = new LegacyCreateScoreRequest()
			.sessionId(conversationId)
			.name(SessionSentiment.SCORE_NAME)
			.value(CreateScoreValue.of(sentiment.sentiment().label()))
			.dataType(ScoreDataType.CATEGORICAL)
			.comment(sentiment.reasoning());

		try {
			var response = this.langfuseApiClient.legacyScoreV1Create(request);
			Log.infof("Posted session-sentiment score for session %s (scoreId=%s)", conversationId, response.getId());
		}
		catch (Exception e) {
			Log.warnf(e, "Failed to post session-sentiment score for session %s: %s", conversationId, e.getMessage());
		}
	}
}
