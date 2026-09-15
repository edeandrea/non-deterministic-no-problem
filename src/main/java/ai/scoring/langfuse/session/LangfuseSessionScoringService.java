package ai.scoring.langfuse.session;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.langfuse.api.model.ObservationV2;
import com.langfuse.api.model.ScoreDataType;
import com.langfuse.api.observations.ObservationsApi.APIObservationsGetManyRequest;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.quarkiverse.langfuse.client.LangfuseNotFoundException;
import io.smallrye.mutiny.Uni;

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

			// Fetch *all* observations for the session, not just the generations. The extra ones (the AI service root
			// span, tool spans, ...) are what let ConversationExchange walk up from each generation to its enclosing
			// langchain4j.aiservices.* span to work out which dataset it belongs in.
			var sessionObservations = awaitSessionObservations(conversationId);
			var hierarchy = ConversationExchange.hierarchyOf(sessionObservations);

			// Only observations with both input and output (i.e. the generations) become exchanges
			sessionObservations.stream()
			                .filter(LangfuseSessionScoringService::isCompleteExchange)
			                .sorted(Comparator.comparing(ObservationV2::getStartTime))
			                .map(obs -> ConversationExchange.from(obs, hierarchy))
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

	private static boolean isCompleteExchange(ObservationV2 observation) {
		return (observation.getStartTime() != null) && (observation.getInput() != null) && (observation.getOutput() != null);
	}

	/**
	 * Spans reach Langfuse asynchronously twice over: the OpenTelemetry batch exporter flushes on its own schedule,
	 * and Langfuse then ingests OTLP traces into ClickHouse asynchronously. So the session's observations are
	 * usually <em>not</em> queryable the instant the conversation ends - a single query fired straight away would
	 * typically come back empty and the session would silently go unscored.
	 * <p>
	 * Instead: wait the initial flush period, then poll until at least one complete exchange (an observation with
	 * both input and output) is visible, or the max wait elapses. On timeout an empty list is returned so the
	 * caller's existing "nothing to score" handling applies.
	 * <p>
	 * Implemented as a Mutiny pipeline and then collapsed back to blocking with {@code await()}, purely because
	 * {@code retry().withBackOff().expireIn()} expresses "poll until condition or deadline" more clearly than a
	 * hand-rolled loop with sleeps, deadline arithmetic and interrupt handling. This method is always called on a
	 * background worker thread (see {@code ConversationalBaggageHandler}), so blocking here is fine.
	 */
	private List<ObservationV2> awaitSessionObservations(String conversationId) {
		var sessionConfig = this.langfuseConfig.evaluation().session();

		return Uni.createFrom().item(conversationId)
			// Give the OTel batch exporter a head start before the first query
			.onItem().delayIt().by(sessionConfig.otelFlushWaitTime())
			.flatMap(this::fetchSessionObservations)

			// Mutiny's retry() is failure-driven, so "not ready yet" has to be surfaced as a failure to be retried on
			.flatMap(observations ->
				observations.stream().anyMatch(LangfuseSessionScoringService::isCompleteExchange) ?
					Uni.createFrom().item(observations) :
					Uni.createFrom().failure(new ObservationsNotReadyException(conversationId))
			)

			// Re-run the fetch every pollInterval until the deadline. withBackOff(x, x) pins the delay to a constant x;
			// the default is exponential with jitter, which isn't what we want for "has it landed yet?" polling.
			.onFailure(ObservationsNotReadyException.class)
				.retry()
				.withBackOff(sessionConfig.observationPollInterval(), sessionConfig.observationPollInterval())
				.expireIn(sessionConfig.observationMaxWaitTime().toMillis())

			// Still not ready when the retries expired: log and hand back an empty list rather than propagating
			.onFailure(ObservationsNotReadyException.class)
				.recoverWithItem(() -> {
					Log.warnf("Gave up waiting for observations for session %s after %s", conversationId, sessionConfig.observationMaxWaitTime());
					return List.of();
				})

			// Collapse back to blocking. This timeout is a backstop for the *whole* pipeline (including the initial
			// delay and an in-flight HTTP call at the deadline), so it's deliberately a little larger than expireIn.
			.await().atMost(sessionConfig.otelFlushWaitTime().plus(sessionConfig.observationMaxWaitTime()).plusSeconds(5));
	}

	private Uni<List<ObservationV2>> fetchSessionObservations(String conversationId) {
		var sessionFilter = """
			[{"type":"string","column":"sessionId","operator":"=","value":"%s"}]""".formatted(conversationId);
		var request = APIObservationsGetManyRequest.newBuilder()
		                                           .filter(sessionFilter)
		                                           .fields("core,basic,io,meta")
		                                           .build();

		// Deferred supplier so each retry issues a fresh request rather than replaying the first response
		return Uni.createFrom().completionStage(() -> this.langfuseApi.asyncObservations().observationsGetMany(request))
			// Langfuse returns an empty body ({}) rather than an empty data array when nothing matches yet
			.map(response -> Optional.ofNullable(response.getData()).orElseGet(List::of));
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
			var datasetName = exchange.datasetName();

			if (existingDatasets.add(datasetName)) {
				var request = CreateDatasetRequest.builder()
					.name(datasetName)
					.build();

				datasetsApi.datasetsCreate(APIDatasetsCreateRequest.newBuilder()
					.createDatasetRequest(request)
					.build());
				Log.infof("Created dataset '%s'", datasetName);
			}

			var metadata = Map.of(
						"session_id", conversationId,
						"trace_id", exchange.traceId(),
						"trace_name", exchange.traceName(),
						"dataset_name", datasetName
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

	/**
	 * Signals that Langfuse has not yet ingested any complete exchange (an observation with both input and output)
	 * for a session.
	 * <p>
	 * This is a <em>normal</em> transient state, not an error: spans reach Langfuse asynchronously and take a few
	 * seconds to become queryable. It is modelled as an exception purely because Mutiny's {@code retry()} operator is
	 * failure-driven, so "not ready yet" has to surface as a failure for {@link #awaitSessionObservations} to retry on it.
	 * It never escapes that method.
	 */
	private static class ObservationsNotReadyException extends RuntimeException {
		ObservationsNotReadyException(String conversationId) {
			super("No complete exchanges yet for session %s".formatted(conversationId));
		}
	}
}
