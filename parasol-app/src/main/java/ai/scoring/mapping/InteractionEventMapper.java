package ai.scoring.mapping;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import ai.scoring.config.ScoringConfig;
import ai.scoring.scorer.model.InteractionCompletedEvent;
import ai.scoring.scorer.model.InteractionEvent;
import ai.scoring.scorer.model.InteractionEvent.InteractionModeEnum;
import ai.scoring.scorer.model.InteractionStartedEvent;
import dev.langchain4j.data.message.ContentType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.observability.api.event.AiServiceCompletedEvent;
import dev.langchain4j.observability.api.event.AiServiceEvent;
import dev.langchain4j.observability.api.event.AiServiceStartedEvent;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Application;

@ApplicationScoped
public class InteractionEventMapper {
	private final ObjectMapper objectMapper;
	private final ScoringConfig scoringConfig;

	public InteractionEventMapper(ObjectMapper objectMapper, ScoringConfig scoringConfig) {
		this.objectMapper = objectMapper;
		this.scoringConfig = scoringConfig;
	}

	public InteractionEvent map(AiServiceEvent event) {
		Log.debugf("Mapping event: %s", event.eventClass().getName());

		return switch(event) {
			case AiServiceStartedEvent e -> map(e);
			case AiServiceCompletedEvent e -> map(e);
			default -> throw new IllegalArgumentException("Unsupported event type: " + event.getClass().getName());
		};
	}

	public InteractionCompletedEvent map(AiServiceCompletedEvent event) {
		var e = new InteractionCompletedEvent();
		e.setInteractionType("completed");

		event.result()
			.map(this::toJson)
			.ifPresent(e::setResult);

		map(event.invocationContext(), e);

		return e;
	}

	public InteractionStartedEvent map(AiServiceStartedEvent event) {
		var e = new InteractionStartedEvent();
		e.setInteractionType("started");
		e.setSystemMessage(event.systemMessage().map(SystemMessage::text).orElse(""));

		var userMessage = Optional.ofNullable(event.userMessage())
		        .map(UserMessage::contents)
		        .map(List::stream)
		        .orElseGet(Stream::empty)
		        .filter(content -> content.type() == ContentType.TEXT)
		        .map(TextContent.class::cast)
		        .map(TextContent::text)
		        .collect(Collectors.joining());

		e.setUserMessage(userMessage);
		map(event.invocationContext(), e);

		return e;
	}

	private void map(InvocationContext invocationContext, InteractionEvent interactionEvent) {
		interactionEvent.setInteractionId(invocationContext.invocationId());
		interactionEvent.setInteractionDate(invocationContext.timestamp());
		interactionEvent.setInterfaceName(invocationContext.interfaceName());
		interactionEvent.setMethodName(invocationContext.methodName());
		interactionEvent.setApplicationName(Application.currentApplication().getName());
		interactionEvent.setInteractionMode(InteractionModeEnum.fromString(this.scoringConfig.interactionMode().name()));
	}

	private String toJson(Object object) {
		try {
			return this.objectMapper.writeValueAsString(object);
		}
		catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}
	}
}
