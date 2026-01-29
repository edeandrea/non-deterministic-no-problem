package ai.scoring;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import org.eclipse.microprofile.rest.client.inject.RestClient;

import ai.scoring.config.ScoringConfig;
import ai.scoring.mapping.InteractionEventMapper;
import ai.scoring.mapping.RescoreInteractionResultMapper;
import ai.scoring.rescore.RescoreBelowThresholdException;
import ai.scoring.scorer.api.AiInteractionsApi;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.observability.api.event.AiServiceCompletedEvent;
import dev.langchain4j.observability.api.event.AiServiceEvent;
import dev.langchain4j.observability.api.event.AiServiceStartedEvent;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.annotations.WithSpan;

import io.quarkus.logging.Log;

import io.smallrye.mutiny.infrastructure.Infrastructure;

@ApplicationScoped
public class InteractionPublisher {
	private final AiInteractionsApi aiInteractionApi;
	private final InteractionEventMapper interactionEventMapper;
	private final RescoreInteractionResultMapper rescoreInteractionResultMapper;
	private final ScoringConfig scoringConfig;

	public InteractionPublisher(@RestClient AiInteractionsApi aiInteractionApi, InteractionEventMapper interactionEventMapper, RescoreInteractionResultMapper rescoreInteractionResultMapper, ScoringConfig scoringConfig) {
		this.aiInteractionApi = aiInteractionApi;
		this.interactionEventMapper = interactionEventMapper;
		this.rescoreInteractionResultMapper = rescoreInteractionResultMapper;
		this.scoringConfig = scoringConfig;
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
		Log.info("before firing");

		switch (this.scoringConfig.interactionMode()) {
			case NORMAL -> handleNormalEventFiring(event);
			case RESCORE -> handleRescoreEventFiring(event);
		}

		Log.info("after firing");
	}

	private void handleRescoreEventFiring(AiServiceEvent event) {
		// We're in "rescore" mode, so when we fire the event we care about the result
		// We need to wait for the result before continuing
		// And if the score is below the threshold, we want to blow up
		var response = this.aiInteractionApi.submitInteractionEvent(this.interactionEventMapper.map(event));

		Log.infof("Rescore result: %s", response.getScore());

		if (response.getScore() <= this.scoringConfig.threshold()) {
			throw new RescoreBelowThresholdException(this.rescoreInteractionResultMapper.map(response), this.scoringConfig.threshold());
		}
	}

	private void handleNormalEventFiring(AiServiceEvent event) {
		// We're in "normal" mode - so just fire and forget
		// Offload everything to another thread
		// We don't care about the result/failure/etc
		Infrastructure.getDefaultExecutor().execute(() -> {
			try {
				this.aiInteractionApi.submitInteractionEvent(this.interactionEventMapper.map(event));
			}
			catch (Exception error) {
				Log.errorf(error, "Error publishing interaction event for context: %s", event.invocationContext());
			}
		});
	}
}
