package ai.scoring;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.Response.Status.Family;

import org.eclipse.microprofile.rest.client.inject.RestClient;

import ai.scoring.config.ScoringConfig;
import ai.scoring.mapping.InteractionEventMapper;
import ai.scoring.mapping.RescoreInteractionResultMapper;
import ai.scoring.rescore.RescoreBelowThresholdException;
import ai.scoring.scorer.api.AiInteractionsApi;
import ai.scoring.scorer.model.SubmitInteractionEvent200Response;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.observability.api.event.AiServiceCompletedEvent;
import dev.langchain4j.observability.api.event.AiServiceEvent;
import dev.langchain4j.observability.api.event.AiServiceStartedEvent;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.annotations.WithSpan;

import io.quarkus.logging.Log;

import io.smallrye.mutiny.infrastructure.Infrastructure;

@ApplicationScoped
public class InteractionPublisher {
	private final AiInteractionsApi aiInteractionApi;
	private final InteractionEventMapper interactionEventMapper;
	private final RescoreInteractionResultMapper rescoreInteractionResultMapper;
	private final ScoringConfig scoringConfig;
	private final Tracer tracer;

	public InteractionPublisher(@RestClient AiInteractionsApi aiInteractionApi, InteractionEventMapper interactionEventMapper, RescoreInteractionResultMapper rescoreInteractionResultMapper, ScoringConfig scoringConfig, Tracer tracer) {
		this.aiInteractionApi = aiInteractionApi;
		this.interactionEventMapper = interactionEventMapper;
		this.rescoreInteractionResultMapper = rescoreInteractionResultMapper;
		this.scoringConfig = scoringConfig;
		this.tracer = tracer;
	}

	@WithSpan(value = "publishInteractionStartedEvent", kind = SpanKind.CLIENT)
	public void serviceStarted(@Observes AiServiceStartedEvent e) {
		Log.debugf(
			"Interaction started:\ncontext: %s\nsystemMessage: %s\nuserMessage: %s",
			e.invocationContext(),
			e.systemMessage().map(SystemMessage::text).orElse(""),
			e.userMessage().singleText()
		);

		fireEvent(e);
	}

	@WithSpan(value = "publishInteractionCompletedEvent", kind = SpanKind.CLIENT)
	public void serviceCompleted(@Observes AiServiceCompletedEvent e) {
		Log.debugf(
			"Interaction complete:\ncontext: %s\nresult: %s",
			e.invocationContext(),
			e.result()
		);

		fireEvent(e);
	}

	private void fireEvent(AiServiceEvent event) {
//		Log.info("before firing");
		switch (this.scoringConfig.interactionMode()) {
			case NORMAL -> handleNormalEventFiring(event);
			case RESCORE -> handleRescoreEventFiring(event);
		}
//		Log.info("after firing");
	}

	private void handleRescoreEventFiring(AiServiceEvent event) {
		// We're in "rescore" mode, so when we fire the event we care about the result
		// We need to wait for the result before continuing
		// And if the score is below the threshold, we want to blow up
		var response = this.aiInteractionApi.submitInteractionEvent(this.interactionEventMapper.map(event));
		var responseStatusFamily = response.getStatusInfo().getFamily();

		Log.debug("Got rescore response back");

		if ((responseStatusFamily == Family.SUCCESSFUL) && response.hasEntity()) {
			Optional.ofNullable(response.readEntity(SubmitInteractionEvent200Response.class))
				.ifPresent(resp -> {
					Log.infof("Rescore result: %s", resp.getScore());

					if (resp.getScore() <= this.scoringConfig.threshold()) {
						Log.errorf("Rescore [%s] below threshold: [%s]", resp.getScore(), this.scoringConfig.threshold());
						// For now, this doesn't get propagated
						// Due to https://github.com/langchain4j/langchain4j/issues/4499
						var e = new RescoreBelowThresholdException(this.rescoreInteractionResultMapper.map(resp), this.scoringConfig.threshold());
						Log.errorf(e, e.getMessage());

						throw e;
					}
				});
		}
		else if ((responseStatusFamily == Family.CLIENT_ERROR) || (responseStatusFamily == Family.SERVER_ERROR)) {
			Log.errorf("Error publishing interaction event for context: %s", event.invocationContext());
		}
	}

	private void handleNormalEventFiring(AiServiceEvent event) {
		// We're in "normal" mode - so just fire and forget
		// Offload everything to another thread
		// We don't care about the result/failure/etc
		Infrastructure.getDefaultExecutor().execute(Context.current().wrap(() -> {
			Span span = this.tracer.spanBuilder("processInteractionScore")
				.setSpanKind(SpanKind.INTERNAL)
				.startSpan();

			try (var scope = span.makeCurrent()) {
				var response = this.aiInteractionApi.submitInteractionEvent(this.interactionEventMapper.map(event));
				var responseStatusFamily = response.getStatusInfo().getFamily();

				if ((responseStatusFamily == Family.SUCCESSFUL) && (response.getStatus() == Status.OK.getStatusCode())) {
					Optional.ofNullable(response)
						.filter(Response::hasEntity)
						.map(resp -> resp.readEntity(SubmitInteractionEvent200Response.class))
						.ifPresent(resp -> {
							Log.infof("Interaction score: %s", resp.getScore());
							span.setAttribute("score", resp.getScore());
						});
				}
				else if ((responseStatusFamily == Family.CLIENT_ERROR) || (responseStatusFamily == Family.SERVER_ERROR)) {
					Log.errorf("Error publishing interaction event for context: %s", event.invocationContext());
				}
			}
			catch (Exception error) {
				span.recordException(error);
				Log.errorf(error, "Error publishing interaction event for context: %s", event.invocationContext());
			}
			finally {
				span.end();
			}
		}));
	}
}
