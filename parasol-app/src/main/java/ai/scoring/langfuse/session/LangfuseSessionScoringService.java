package ai.scoring.langfuse.session;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;

import org.eclipse.microprofile.rest.client.inject.RestClient;

import ai.scoring.conversation.ConversationCompletedEvent;
import ai.scoring.langfuse.rest.LangfuseApiClient;
import ai.scoring.langfuse.rest.LangfuseNotFoundException;
import ai.scoring.langfuse.rest.model.CreateDatasetItemRequest;
import ai.scoring.langfuse.rest.model.CreateDatasetRequest;
import ai.scoring.langfuse.rest.model.CreateScoreValue;
import ai.scoring.langfuse.rest.model.LegacyCreateScoreRequest;
import ai.scoring.langfuse.rest.model.ScoreDataType;
import ai.scoring.langfuse.rest.model.Dataset;
import ai.scoring.langfuse.rest.model.Trace;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;

import io.quarkus.logging.Log;

/**
 * Service responsible for scoring sessions by analyzing conversation sentiment.
 * The service receives an async {@link ConversationCompletedEvent}, processes session data,
 * evaluates the sentiment, and submits the calculated score to Langfuse as a session score.
 *
 * The scoring flow includes:
 * - Observing conversation completion events.
 * - Fetching session traces through the langfuse REST API.
 * - Filtering and sorting relevant traces.
 * - Performing sentiment evaluation based on conversation exchanges.
 * - Posting the sentiment score back to Langfuse
 */
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
			// This is to give time for OTEL to flush spans
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
				.map(ConversationExchange::from)
				.collect(Collectors.collectingAndThen(
					Collectors.toUnmodifiableList(),
					exchanges -> Optional.ofNullable(exchanges)
						.filter(e -> !e.isEmpty())
						.map(e -> createDatasets(conversationId, e))
						.map(this.sessionSentimentService::evaluate)
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

	private List<ConversationExchange> createDatasets(String conversationId, List<ConversationExchange> exchanges) {
		// This probably isn't the best way to do this
		// Its essentially building a local cache, which if lots of apps are running concurrently, could mean that new datasets are added while performing this logic
		// It would be better to try to fetch datasets and check each time, but this is simpler and should be fine for now
		// #Demoware!
		var existingDatasets = this.langfuseApiClient.datasetsList(null, null)
			.getData()
			.stream()
			.map(Dataset::getName)
			.collect(Collectors.toSet());

		exchanges.forEach(exchange -> {
			var datasetName = "%s/%s".formatted(exchange.traceName(), conversationId);

			if (existingDatasets.add(datasetName)) {
				this.langfuseApiClient.datasetsCreate(new CreateDatasetRequest().name(datasetName));
				Log.infof("Created dataset %s for session %s", datasetName, conversationId);
			}

			this.langfuseApiClient.datasetItemsCreate(new CreateDatasetItemRequest().datasetName(datasetName)
			                                                                        .input(exchange.input())
			                                                                        .expectedOutput(exchange.output())
			                                                                        .sourceTraceId(exchange.traceId()));
		});

		return exchanges;
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
