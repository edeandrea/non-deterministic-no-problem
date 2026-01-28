package ai.scoring;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import org.eclipse.microprofile.rest.client.inject.RestClient;

import ai.scoring.mapping.InteractionEventMapper;
import ai.scoring.scorer.api.AiInteractionApi;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.observability.api.event.AiServiceCompletedEvent;
import dev.langchain4j.observability.api.event.AiServiceEvent;
import dev.langchain4j.observability.api.event.AiServiceStartedEvent;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.annotations.WithSpan;

import io.quarkus.logging.Log;

import io.smallrye.mutiny.Uni;

@ApplicationScoped
public class InteractionPublisher {
	private final AiInteractionApi aiInteractionApi;
	private final InteractionEventMapper interactionEventMapper;

	public InteractionPublisher(@RestClient AiInteractionApi aiInteractionApi, InteractionEventMapper interactionEventMapper) {
		this.aiInteractionApi = aiInteractionApi;
		this.interactionEventMapper = interactionEventMapper;
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

		Uni.createFrom().item(() -> this.interactionEventMapper.map(event))
		   .flatMap(this.aiInteractionApi::submitInteractionEvent)
		   .onFailure().invoke(error -> Log.errorf(error, "Error publishing interaction event for context: %s", event.invocationContext()))
		   .subscribeAsCompletionStage();

		Log.info("after firing");
	}
}
