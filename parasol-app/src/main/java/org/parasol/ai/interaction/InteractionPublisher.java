package org.parasol.ai.interaction;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.core.Response.Status.Family;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.parasol.mapping.InteractionEventMapper;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.observability.api.event.AiServiceCompletedEvent;
import dev.langchain4j.observability.api.event.AiServiceEvent;
import dev.langchain4j.observability.api.event.AiServiceStartedEvent;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkiverse.aiinteractions.event.api.AiInteractionApi;

import io.quarkus.logging.Log;

import io.smallrye.mutiny.infrastructure.Infrastructure;

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
		Infrastructure.getDefaultExecutor().execute(() -> {
			var e = this.interactionEventMapper.map(event);
			var response = this.aiInteractionApi.submitInteractionEvent(e);

			switch (response.getStatusInfo().getFamily()) {
				case Family.SUCCESSFUL:
					Log.infof("Successfully published interaction event for context: %s", event.invocationContext());
					break;

				case Family.CLIENT_ERROR:
				case Family.SERVER_ERROR:
					Log.errorf("Got %d when publishing interaction event for context: %s", response.getStatusInfo().getStatusCode(), event.invocationContext());
					break;
			}
		});
	}
}
