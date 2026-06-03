package ai.scoring.langfuse.session;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;

import io.quarkus.logging.Log;

import ai.scoring.conversation.ConversationCompletedEvent;
import ai.scoring.langfuse.config.LangfuseConfig;
import com.langfuse.api.LangfuseApi;
import com.langfuse.api.datasetItems.DatasetItemsApi.APIDatasetItemsCreateRequest;
import com.langfuse.api.datasets.DatasetsApi.APIDatasetsCreateRequest;
import com.langfuse.api.datasets.DatasetsApi.APIDatasetsListRequest;
import com.langfuse.api.legacyScoreV1.LegacyScoreV1Api.APILegacyScoreV1CreateRequest;
import com.langfuse.api.model.CreateDatasetItemRequest;
import com.langfuse.api.model.CreateDatasetRequest;
import com.langfuse.api.model.CreateScoreValue;
import com.langfuse.api.model.Dataset;
import com.langfuse.api.model.LegacyCreateScoreRequest;
import com.langfuse.api.model.ScoreDataType;
import com.langfuse.api.model.Trace;
import com.langfuse.api.sessions.SessionsApi.APISessionsGetRequest;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.quarkiverse.langfuse.client.LangfuseNotFoundException;

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

	public void onConversationCompleted(@ObservesAsync ConversationCompletedEvent event) {
		var conversationId = event.getConversationId();
		Log.infof("Conversation %s completed - scoring conversation", conversationId);

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
		var datasetsApi = this.langfuseApi.datasets();
		var existingDatasets = datasetsApi.datasetsList(APIDatasetsListRequest.newBuilder().build())
		                                       .getData()
		                                       .stream()
		                                       .map(Dataset::getName)
		                                       .collect(Collectors.toSet());

		exchanges.forEach(exchange -> {
			var datasetName = "%s/%s".formatted(exchange.traceName(), conversationId);

			if (existingDatasets.add(datasetName)) {
				var request = CreateDatasetRequest.builder()
					.name(datasetName)
					.build();

				datasetsApi.datasetsCreate(APIDatasetsCreateRequest.newBuilder()
					.createDatasetRequest(request)
					.build());
				Log.infof("Created dataset %s for session %s", datasetName, conversationId);
			}

			var request = CreateDatasetItemRequest.builder()
				.datasetName(datasetName)
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
		var request = LegacyCreateScoreRequest.builder()
			.sessionId(conversationId)
			.name(SessionSentiment.SCORE_NAME)
			.value(new CreateScoreValue(sentiment.sentiment().label()))
			.dataType(ScoreDataType.CATEGORICAL)
			.comment(sentiment.reasoning())
			.build();

		try {
			var response = this.langfuseApi.legacyScoreV1()
				.legacyScoreV1Create(APILegacyScoreV1CreateRequest.newBuilder()
					.legacyCreateScoreRequest(request)
					.build());
			Log.infof("Posted session-sentiment score for session %s (scoreId=%s)", conversationId, response.getId());
		}
		catch (Exception e) {
			Log.warnf(e, "Failed to post session-sentiment score for session %s: %s", conversationId, e.getMessage());
		}
	}
}
