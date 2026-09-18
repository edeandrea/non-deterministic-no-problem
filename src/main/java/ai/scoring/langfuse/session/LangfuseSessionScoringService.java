package ai.scoring.langfuse.session;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.logging.Log;

import ai.scoring.evaluation.SessionScoringService;
import ai.scoring.langfuse.config.LangfuseConfig;
import com.langfuse.api.LangfuseApiException;
import com.langfuse.api.model.CreateDatasetItemRequest;
import com.langfuse.api.model.CreateDatasetRequest;
import com.langfuse.api.model.CreateScoreRequest;
import com.langfuse.api.model.CreateScoreValue;
import com.langfuse.api.model.ObservationV2;
import com.langfuse.api.model.ScoreDataType;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.quarkiverse.langfuse.api.LangfuseOperations;
import io.quarkiverse.langfuse.api.ObservationFilter;
import io.quarkiverse.langfuse.client.LangfuseNotFoundException;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.TimeoutException;
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
	private final LangfuseOperations langfuse;
	private final SessionSentimentService sessionSentimentService;

	public LangfuseSessionScoringService(LangfuseConfig langfuseConfig, Tracer tracer, LangfuseOperations langfuse, SessionSentimentService sessionSentimentService) {
		this.langfuseConfig = langfuseConfig;
		this.tracer = tracer;
		this.langfuse = langfuse;
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

		var pendingObservations = Uni.createFrom().item(conversationId)
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

			// Anything else that got this far (an API error, or paging that ran past the deadline mid-traversal) is
			// still only a failure to gather best-effort telemetry: degrade to "nothing to score" rather than letting
			// it escape onto the background worker thread. Must stay *after* the not-ready recovery above so that the
			// more specific "gave up waiting" message still wins for the ordinary polling timeout.
			.onFailure()
				.recoverWithItem(failure -> {
					Log.warnf(failure, "Failed to fetch observations for session %s: %s", conversationId, describeFailure(failure));
					return List.of();
				});

		// Collapse back to blocking. This timeout is a backstop for the *whole* pipeline (including the initial
		// delay and an in-flight paged traversal at the deadline - expireIn() doesn't cancel one mid-flight), so it's
		// deliberately a little larger than expireIn. It is raised by the blocking collapse itself rather than inside
		// the pipeline, so the recoveries above can't see it and it has to be caught here.
		var backstop = sessionConfig.otelFlushWaitTime().plus(sessionConfig.observationMaxWaitTime()).plusSeconds(5);

		try {
			return pendingObservations.await().atMost(backstop);
		}
		catch (TimeoutException e) {
			Log.warnf("Timed out after %s collecting observations for session %s", backstop, conversationId);
			return List.of();
		}
	}

	/**
	 * Langfuse API failures carry the raw JSON response body in {@code getMessage()}, so prefer the status code and
	 * server message for anything we log.
	 */
	private static String describeFailure(Throwable failure) {
		return switch (failure) {
			case LangfuseApiException apiFailure -> "HTTP %s - %s".formatted(apiFailure.getStatusCode(), apiFailure.getServerMessage());
			default -> failure.getMessage();
		};
	}

	private Uni<List<ObservationV2>> fetchSessionObservations(String conversationId) {
		// Exactly the field groups this code consumes, and no more: `core` and `basic` carry startTime and
		// parentObservationId (isCompleteExchange, ConversationExchange.hierarchyOf), `io` carries input/output
		// (isCompleteExchange, ConversationExchange.from), and `metadata` feeds steps 1-2 of
		// ConversationExchange.resolveDatasetName, which fall back to the span-name-based step 3 when metadata is
		// absent. Nothing here reads model or usage, so those groups are deliberately not requested. Note the
		// parameter is free-form text passed through unvalidated: an unknown group is silently ignored rather than
		// rejected, which is exactly how the previous `meta` typo (the group is spelled `metadata`) went unnoticed.
		var filter = ObservationFilter.builder()
			.sessionId(conversationId)
			.fields("core,basic,io,metadata")
			.build();

		// deferred() is load-bearing, not stylistic: this Uni is the retried step of the polling pipeline in
		// awaitSessionObservations, so it must issue a *new* query on every re-subscription. Handing back an
		// already-started Uni would make every retry replay the first (empty) response and the session would
		// never be scored.
		return Uni.createFrom().deferred(() -> this.langfuse.async()
			.observations()
			.matching(filter)
			.findAll());
	}

	private SessionSentiment evaluateSession(List<ConversationExchange> exchanges) {
		Log.info("Conversation completed - scoring conversation");
		return this.sessionSentimentService.evaluate(exchanges);
	}

	private List<ConversationExchange> createDatasets(String conversationId, List<ConversationExchange> exchanges) {
		// createIfAbsent looks the dataset up and only creates it when genuinely absent, so each exchange gets a fresh
		// check against Langfuse rather than a snapshot taken once at the start. It is *not* atomic (a lookup followed
		// by a create), so two processes can still collide; what it removes is the stale-snapshot problem.
		//
		// Because it isn't atomic, creating several datasets concurrently would let it race against *itself* whenever
		// two exchanges share a dataset name. Names are therefore deduplicated and created one after another
		// (concatenate), while the items - one per exchange, no contention between them - are created in parallel.
		var distinctDatasetNames = exchanges.stream()
		                                    .map(ConversationExchange::datasetName)
		                                    .distinct()
		                                    .toList();

		// Each Uni<Void> emits null, which flattens to nothing, so the collected list is always empty: it is used only
		// as a "all datasets have been created" completion signal, never for its contents.
		var datasetsReady = Multi.createFrom().iterable(distinctDatasetNames)
			.onItem().transformToUni(this::createDatasetIfAbsent)
			.concatenate()
			.collect().asList()
			.replaceWithVoid();

		var itemCreations = exchanges.stream()
		                             .map(exchange -> createDatasetItem(conversationId, exchange))
		                             .toList();

		// Recording datasets is best-effort: it must never abort scoring. Failures (including the CompositeException
		// andFailFast() can raise, and the TimeoutException from the bounded await) are logged and swallowed here
		// rather than propagating to fetchAndScoreSession, which only downgrades LangfuseNotFoundException.
		try {
			datasetsReady
				.chain(() -> Uni.join().all(itemCreations).andFailFast())
				// Bounded: a hung Langfuse call must not pin this worker thread forever
				.await().atMost(this.langfuseConfig.evaluation().session().datasetCreationMaxWaitTime());
		}
		catch (TimeoutException e) {
			Log.warnf("Gave up waiting for datasets of session %s to be recorded after %s", conversationId, this.langfuseConfig.evaluation().session().datasetCreationMaxWaitTime());
		}
		catch (LangfuseApiException e) {
			// getMessage() carries the whole raw response body; getServerMessage() is the server's own sentence.
			Log.warnf(e, "Failed to record datasets for session %s (HTTP %d): %s", conversationId, e.getStatusCode(), e.getServerMessage());
		}
		catch (Exception e) {
			Log.warnf(e, "Failed to record datasets for session %s: %s", conversationId, e.getMessage());
		}

		return exchanges;
	}

	private Uni<Void> createDatasetIfAbsent(String datasetName) {
		var request = CreateDatasetRequest.builder()
			.name(datasetName)
			.build();

		return this.langfuse.async()
			.datasets()
			.createIfAbsent(request)
			.invoke(() -> Log.infof("Dataset '%s' ready", datasetName))
			.replaceWithVoid();
	}

	private Uni<Void> createDatasetItem(String conversationId, ConversationExchange exchange) {
		var datasetName = exchange.datasetName();
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

		// The created DatasetItem is discarded: callers only need the completion signal, since createDatasets
		// joins these Unis purely to know when every item has landed.
		return this.langfuse.async()
			.datasetItems()
			.create(request)
			.replaceWithVoid();
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
			// Deliberately the synchronous operations tree: this runs on a background worker thread and the score
			// must be posted before the enclosing ComputeSessionScore span is ended by scoreSession().
			var response = this.langfuse.scores().create(request);
			Log.infof("Posted session-sentiment score for session %s (scoreId=%s)", conversationId, response.getId());
		}
		catch (LangfuseApiException e) {
			// getMessage() carries the whole raw response body; getServerMessage() is the server's own sentence.
			Log.warnf(e, "Failed to post session-sentiment score for session %s (HTTP %d): %s", conversationId, e.getStatusCode(), e.getServerMessage());
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
