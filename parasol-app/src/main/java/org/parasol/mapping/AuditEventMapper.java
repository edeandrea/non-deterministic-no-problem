package org.parasol.mapping;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;

import org.parasol.model.audit.InputGuardrailExecutedAuditEvent;
import org.parasol.model.audit.InvocationContext;
import org.parasol.model.audit.OutputGuardrailExecutedAuditEvent;
import org.parasol.model.audit.ResponseReceivedAuditEvent;
import org.parasol.model.audit.ServiceCompleteAuditEvent;
import org.parasol.model.audit.ServiceErrorAuditEvent;
import org.parasol.model.audit.ServiceStartedAuditEvent;
import org.parasol.model.audit.ToolExecutedAuditEvent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.observability.api.event.AiServiceCompletedEvent;
import dev.langchain4j.observability.api.event.AiServiceErrorEvent;
import dev.langchain4j.observability.api.event.AiServiceResponseReceivedEvent;
import dev.langchain4j.observability.api.event.AiServiceStartedEvent;
import dev.langchain4j.observability.api.event.InputGuardrailExecutedEvent;
import dev.langchain4j.observability.api.event.OutputGuardrailExecutedEvent;
import dev.langchain4j.observability.api.event.ToolExecutedEvent;

@ApplicationScoped
public class AuditEventMapper {
	private final ObjectMapper objectMapper;

	public AuditEventMapper(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public ServiceStartedAuditEvent toAuditEvent(AiServiceStartedEvent serviceStartedEvent) {
		return ServiceStartedAuditEvent.builder()
		                               .invocationContext(toInvocationContext(serviceStartedEvent.invocationContext()))
		                               .systemMessage(fromSystemMessage(serviceStartedEvent.systemMessage()))
		                               .userMessage(fromUserMessage(serviceStartedEvent.userMessage()))
		                               .build();
	}

	public ServiceCompleteAuditEvent toAuditEvent(AiServiceCompletedEvent interactionCompleteEvent) {
		return ServiceCompleteAuditEvent.builder()
		                                .invocationContext(toInvocationContext(interactionCompleteEvent.invocationContext()))
		                                .result(toJson(interactionCompleteEvent.result()))
		                                .build();
	}

	public ServiceErrorAuditEvent toAuditEvent(AiServiceErrorEvent errorEvent) {
		return ServiceErrorAuditEvent.builder()
		                             .invocationContext(toInvocationContext(errorEvent.invocationContext()))
		                             .errorMessage(getMessage(errorEvent.error()))
		                             .causeErrorMessage(getCauseMessage(errorEvent.error()))
		                             .build();
	}

	public ResponseReceivedAuditEvent toAuditEvent(AiServiceResponseReceivedEvent responseReceivedEvent) {
		return ResponseReceivedAuditEvent.builder()
		                                 .invocationContext(toInvocationContext(responseReceivedEvent.invocationContext()))
		                                 .response(fromResponse(responseReceivedEvent.response()))
		                                 .modelName(responseReceivedEvent.response().modelName())
		                                 .inputTokenCount(responseReceivedEvent.response().tokenUsage().inputTokenCount())
		                                 .outputTokenCount(responseReceivedEvent.response().tokenUsage().outputTokenCount())
		                                 .build();
	}

	public ToolExecutedAuditEvent toAuditEvent(ToolExecutedEvent toolExecutedEvent) {
		return ToolExecutedAuditEvent.builder()
			.invocationContext(toInvocationContext(toolExecutedEvent.invocationContext()))
			.result(toolExecutedEvent.resultText())
			.toolName(toolExecutedEvent.request().name())
			.toolArgs(toolExecutedEvent.request().arguments())
			.build();
	}

	public InputGuardrailExecutedAuditEvent toAuditEvent(InputGuardrailExecutedEvent inputGuardrailExecutedEvent) {
		return InputGuardrailExecutedAuditEvent.builder()
			.invocationContext(toInvocationContext(inputGuardrailExecutedEvent.invocationContext()))
			.userMessage(fromUserMessage(inputGuardrailExecutedEvent.request().userMessage()))
			.rewrittenUserMessage(fromUserMessage(inputGuardrailExecutedEvent.rewrittenUserMessage()))
			.result(inputGuardrailExecutedEvent.result().result().name())
			.guardrailClass(inputGuardrailExecutedEvent.guardrailClass().getName())
			.duration(inputGuardrailExecutedEvent.duration())
			.build();
	}

	public OutputGuardrailExecutedAuditEvent toAuditEvent(OutputGuardrailExecutedEvent outputGuardrailExecutedEvent) {
		return OutputGuardrailExecutedAuditEvent.builder()
			.invocationContext(toInvocationContext(outputGuardrailExecutedEvent.invocationContext()))
			.response(Optional.ofNullable(outputGuardrailExecutedEvent.request().responseFromLLM()).map(response -> response.aiMessage().text()).orElse(null))
			.result(outputGuardrailExecutedEvent.result().result().name())
			.guardrailClass(outputGuardrailExecutedEvent.guardrailClass().getName())
			.duration(outputGuardrailExecutedEvent.duration())
			.build();
	}

	private InvocationContext toInvocationContext(dev.langchain4j.invocation.InvocationContext invocationContext) {
		return InvocationContext.builder()
		                        .interfaceName(invocationContext.interfaceName())
		                        .methodName(invocationContext.methodName())
		                        .interactionId(invocationContext.invocationId())
		                        .build();
	}

	private static String fromResponse(ChatResponse response) {
		return Optional.ofNullable(response)
			.map(r -> r.aiMessage().text())
			.or(() ->
				Optional.ofNullable(response.aiMessage().toolExecutionRequests())
					.map(List::stream)
					.flatMap(Stream::findFirst)
					.map(toolExecutionRequest -> "EXECUTE TOOL: %s(%s)".formatted(toolExecutionRequest.name(), toolExecutionRequest.arguments()))
			)
			.orElse(null);
	}

	private static String fromSystemMessage(Optional<SystemMessage> systemMessage) {
		return systemMessage.map(SystemMessage::text).orElse("");
	}

	private static String fromUserMessage(UserMessage userMessage) {
		return (userMessage != null) ?
		       userMessage.singleText() :
		       null;
	}

	String toJson(Object object) {
		try {
			return this.objectMapper.writeValueAsString(object);
		}
		catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}

		//		return this.objectMapper.valueToTree(object);
//		return this.objectMapper.convertValue(object, new TypeReference<>() {});
//		return switch (object) {
//			case null -> null;
//			case String s -> Map.of("string", s);
//			case Number n -> Map.of("number", String.valueOf(n));
//			case Boolean b2 -> Map.of("boolean", String.valueOf(b2));
//			case Object o when o.getClass().isPrimitive() -> Map.of("value", String.valueOf(o));
//			case Object o when o.getClass().isArray() -> Map.of("array", String.valueOf(o));
//			default -> this.objectMapper.convertValue(object, new TypeReference<>() {});
//		};
	}

	private static String getMessage(Throwable t) {
		return Optional.ofNullable(t)
			.map(Throwable::getMessage)
			.orElse(null);
	}

	private static String getCauseMessage(Throwable t) {
		return Optional.ofNullable(t)
			.map(Throwable::getCause)
			.map(AuditEventMapper::getMessage)
			.orElse(null);
	}
}
