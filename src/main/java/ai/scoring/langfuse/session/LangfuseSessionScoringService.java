package ai.scoring.langfuse.session;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.logging.Log;

import ai.scoring.evaluation.SessionScoringService;
import ai.scoring.langfuse.config.LangfuseConfig;
import com.langfuse.api.LangfuseApi;
import com.langfuse.api.datasetItems.DatasetItemsApi.APIDatasetItemsCreateRequest;
import com.langfuse.api.datasets.DatasetsApi.APIDatasetsCreateRequest;
import com.langfuse.api.datasets.DatasetsApi.APIDatasetsListRequest;
import com.langfuse.api.scores.ScoresApi.APIScoresCreateRequest;
import com.langfuse.api.model.CreateDatasetItemRequest;
import com.langfuse.api.model.CreateDatasetRequest;
import com.langfuse.api.model.CreateScoreRequest;
import com.langfuse.api.model.CreateScoreValue;
import com.langfuse.api.model.Dataset;
import com.langfuse.api.model.ScoreDataType;
import com.langfuse.api.model.Trace;
import com.langfuse.api.sessions.SessionsApi.APISessionsGetRequest;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.quarkiverse.langfuse.client.LangfuseNotFoundException;

/**
 * Service responsible for scoring sessions using Langfuse's evaluation and scoring APIs.
 * Implements the {@link SessionScoringService} interface.
 *
 * This service automates the process of fetching session data, creating datasets if necessary,
 * evaluating session sentiment, and recording the derived sentiment scores.
 */
@ApplicationScoped
public class LangfuseSessionScoringService implements SessionScoringService {
	private final LangfuseConfig langfuseConfig;
	private final Tracer tracer;
	private final LangfuseApi langfuseApi;
	private final SessionSentimentService sessionSentimentService;

	public LangfuseSessionScoringService(LangfuseConfig langfuseConfig, Tracer tracer, LangfuseApi langfuseApi, SessionSentimentService sessionSentimentService) {
		this.langfuseConfig = langfuseConfig;
		this.tracer = tracer;
		this.langfuseApi = langfuseApi;
		this.sessionSentimentService = sessionSentimentService;
	}

	@Override
	public void scoreSession(String conversationId) {
		try {
			// This is to give time for OTEL to flush spans
			TimeUnit.MILLISECONDS.sleep(this.langfuseConfig.evaluation().session().otelFlushWaitTime().toMillis());
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
			var sessionEvalConfig = this.langfuseConfig.evaluation().session();

			this.langfuseApi.sessions()
			                .sessionsGet(APISessionsGetRequest.newBuilder()
			                                                  .sessionId(conversationId)
			                                                  .build())
			                .getTraces()
			                .stream()
			                .filter(trace -> (trace.getTimestamp() != null) && (trace.getInput() != null) && (trace.getOutput() != null))
			                .sorted(Comparator.comparing(Trace::getTimestamp))
			                .map(ConversationExchange::from)
			                .collect(Collectors.collectingAndThen(
												Collectors.toUnmodifiableList(),
				                exchanges -> Optional.ofNullable(exchanges)
				                                     .filter(e -> !e.isEmpty())
				                                     .map(e -> sessionEvalConfig.createDatasetOnSessionClose() ? createDatasets(conversationId, e) : e)
				                                     .filter(e -> sessionEvalConfig.scoreSession())
				                                     .map(this::evaluateSession)
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

	private SessionSentiment evaluateSession(List<ConversationExchange> exchanges) {
		Log.info("Conversation completed - scoring conversation");
		return this.sessionSentimentService.evaluate(exchanges);
	}

	private List<ConversationExchange> createDatasets(String conversationId, List<ConversationExchange> exchanges) {
		// This probably isn't the best way to do this
		// Its essentially building a local cache, which if lots of apps are running concurrently, could mean that new datasets are added while performing this logic
		// It would be better to try to fetch datasets and check each time, but this is simpler and should be fine for now
		// #Demoware!
		var datasetsApi = this.langfuseApi.datasets();
		var existingDatasets = datasetsApi.datasetsList(APIDatasetsListRequest.newBuilder().build())
		                                       .getData()
		                                       .stream()
		                                       .map(Dataset::getName)
		                                       .collect(Collectors.toSet());

		exchanges.forEach(exchange -> {
			var datasetName = exchange.traceName();

			if (existingDatasets.add(datasetName)) {
				var request = CreateDatasetRequest.builder()
					.name(datasetName)
					.build();

				datasetsApi.datasetsCreate(APIDatasetsCreateRequest.newBuilder()
					.createDatasetRequest(request)
					.build());
				Log.info("Created dataset");
			}

			var metadata = Map.of(
						"session_id", conversationId,
						"trace_id", exchange.traceId(),
						"trace_name", exchange.traceName()
					);

			var request = CreateDatasetItemRequest.builder()
				.datasetName(datasetName)
				.metadata(metadata)
				.input(exchange.input())
				.expectedOutput(exchange.output())
				.sourceTraceId(exchange.traceId())
				.build();

			this.langfuseApi.datasetItems()
				.datasetItemsCreate(APIDatasetItemsCreateRequest.newBuilder()
					.createDatasetItemRequest(request)
					.build());
		});

		return exchanges;
	}

	private void saveScore(String conversationId, SessionSentiment sentiment) {
		var request = CreateScoreRequest.builder()
			.sessionId(conversationId)
			.name(SessionSentiment.SCORE_NAME)
			.value(new CreateScoreValue(sentiment.sentiment().label()))
			.dataType(ScoreDataType.CATEGORICAL)
			.comment(sentiment.reasoning())
			.build();

		try {
			var response = this.langfuseApi.scores()
				.scoresCreate(APIScoresCreateRequest.newBuilder()
					.createScoreRequest(request)
					.build());
			Log.infof("Posted session-sentiment score for session %s (scoreId=%s)", conversationId, response.getId());
		}
		catch (Exception e) {
			Log.warnf(e, "Failed to post session-sentiment score for session %s: %s", conversationId, e.getMessage());
		}
	}
}
