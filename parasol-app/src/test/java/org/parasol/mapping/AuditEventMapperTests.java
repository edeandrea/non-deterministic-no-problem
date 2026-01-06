package org.parasol.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.parasol.model.audit.AuditEventType;
import org.parasol.model.audit.InputGuardrailExecutedAuditEvent;
import org.parasol.model.audit.InvocationContext;
import org.parasol.model.audit.OutputGuardrailExecutedAuditEvent;
import org.parasol.model.audit.ResponseReceivedAuditEvent;
import org.parasol.model.audit.ServiceCompleteAuditEvent;
import org.parasol.model.audit.ServiceErrorAuditEvent;
import org.parasol.model.audit.ToolExecutedAuditEvent;

import io.quarkus.test.junit.QuarkusTest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.GuardrailRequestParams;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailRequest;
import dev.langchain4j.guardrail.InputGuardrailResult;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.observability.api.event.AiServiceCompletedEvent;
import dev.langchain4j.observability.api.event.AiServiceErrorEvent;
import dev.langchain4j.observability.api.event.AiServiceResponseReceivedEvent;
import dev.langchain4j.observability.api.event.InputGuardrailExecutedEvent;
import dev.langchain4j.observability.api.event.OutputGuardrailExecutedEvent;
import dev.langchain4j.observability.api.event.ToolExecutedEvent;
import io.quarkiverse.langchain4j.guardrails.NoopChatExecutor;
import io.quarkiverse.langchain4j.runtime.aiservice.NoopChatMemory;

@QuarkusTest
class AuditEventMapperTests {
	private static final UUID INVOCATION_ID = UUID.randomUUID();

	private static final dev.langchain4j.invocation.InvocationContext INVOCATION_CONTEXT = dev.langchain4j.invocation.InvocationContext.builder()
		.invocationId(INVOCATION_ID)
		.interfaceName("someInterface")
		.methodName("someMethod")
		.build();

	private static final GuardrailRequestParams GUARDRAIL_REQUEST_PARAMS = GuardrailRequestParams.builder()
			.chatMemory(new NoopChatMemory())
			.userMessageTemplate("do something")
			.invocationContext(INVOCATION_CONTEXT)
			.variables(Map.of())
			.build();

	private static final SomeObject SOME_OBJECT = new SomeObject("field1", 2);
	private static final Exception SOME_ERROR = new RuntimeException("Some error", new NullPointerException("null pointer!"));
	private static final ChatResponse CHAT_RESPONSE = ChatResponse.builder()
		.modelName("someModel")
		.aiMessage(AiMessage.from("Some response"))
		.tokenUsage(new TokenUsage(2, 5, 7))
		.build();

	private static final ToolExecutionRequest TOOL_EXECUTION_REQUEST = ToolExecutionRequest.builder()
		.id("1")
		.name("doSomething")
		.arguments("1")
		.build();

	private static final InputGuardrailRequest INPUT_GUARDRAIL_REQUEST = InputGuardrailRequest.builder()
		.userMessage(UserMessage.from("do something"))
		.commonParams(GUARDRAIL_REQUEST_PARAMS)
		.build();

	private static final OutputGuardrailRequest OUTPUT_GUARDRAIL_REQUEST = OutputGuardrailRequest.builder()
		.responseFromLLM(ChatResponse.builder().aiMessage(AiMessage.from("Some response")).build())
		.chatExecutor(new NoopChatExecutor())
		.requestParams(GUARDRAIL_REQUEST_PARAMS)
		.build();

	@Inject
	AuditEventMapper auditEventMapper;

	@Inject
	ObjectMapper objectMapper;

	private record SomeObject(String field1, int field2) {}

	@Test
	void mapsLLMInteractionComplete() throws JsonProcessingException {
		var event = AiServiceCompletedEvent.builder()
			.invocationContext(INVOCATION_CONTEXT)
			.result(SOME_OBJECT)
			.build();
		var auditEvent = this.auditEventMapper.toAuditEvent(event);

		assertThat(auditEvent)
			.isNotNull()
			.extracting(ServiceCompleteAuditEvent::getEventType)
			.isEqualTo(AuditEventType.SERVICE_COMPLETED);

		checkInvocationContext(auditEvent.getInvocationContext());
		assertThat(this.objectMapper.readValue(auditEvent.getResult(), new TypeReference<Map<String, Object>>() {}))
			.hasSize(2)
			.containsOnly(
				entry("field1", SOME_OBJECT.field1()),
				entry("field2", SOME_OBJECT.field2())
			);
	}

	@Test
	void mapsLLMInteractionFailedAuditEvent() {
		var event = AiServiceErrorEvent.builder()
			.invocationContext(INVOCATION_CONTEXT)
			.error(SOME_ERROR)
			.build();
		var auditEvent = this.auditEventMapper.toAuditEvent(event);

		assertThat(auditEvent)
			.isNotNull()
			.extracting(
				ServiceErrorAuditEvent::getEventType,
				ServiceErrorAuditEvent::getErrorMessage,
				ServiceErrorAuditEvent::getCauseErrorMessage
			)
			.containsExactly(
				AuditEventType.SERVICE_ERROR,
				SOME_ERROR.getMessage(),
				SOME_ERROR.getCause().getMessage()
			);

		checkInvocationContext(auditEvent.getInvocationContext());
	}

	@Test
	void mapsLLMResponseReceivedAuditEvent() {
		var event = AiServiceResponseReceivedEvent.builder()
			.invocationContext(INVOCATION_CONTEXT)
			.response(CHAT_RESPONSE)
			.build();
		var auditEvent = this.auditEventMapper.toAuditEvent(event);

		assertThat(auditEvent)
			.isNotNull()
			.extracting(
				ResponseReceivedAuditEvent::getEventType,
				ResponseReceivedAuditEvent::getResponse,
				ResponseReceivedAuditEvent::getModelName,
				ResponseReceivedAuditEvent::getInputTokenCount,
				ResponseReceivedAuditEvent::getOutputTokenCount,
				ResponseReceivedAuditEvent::getTokenCount
			)
			.containsExactly(
				AuditEventType.RESPONSE_RECEIVED,
				CHAT_RESPONSE.aiMessage().text(),
				CHAT_RESPONSE.modelName(),
				CHAT_RESPONSE.tokenUsage().inputTokenCount(),
				CHAT_RESPONSE.tokenUsage().outputTokenCount(),
				CHAT_RESPONSE.tokenUsage().totalTokenCount()
			);

		checkInvocationContext(auditEvent.getInvocationContext());
	}

	@Test
	void mapsToolExecutedAuditEvent() {
		var event = ToolExecutedEvent.builder()
		                             .invocationContext(INVOCATION_CONTEXT)
		                             .request(TOOL_EXECUTION_REQUEST)
		                             .resultText("result")
		                             .build();
		var auditEvent = this.auditEventMapper.toAuditEvent(event);

		assertThat(auditEvent)
			.isNotNull()
			.extracting(
				ToolExecutedAuditEvent::getEventType,
				ToolExecutedAuditEvent::getToolName,
				ToolExecutedAuditEvent::getToolArgs,
				ToolExecutedAuditEvent::getToolResult
			)
			.containsExactly(
				AuditEventType.TOOL_EXECUTED,
				TOOL_EXECUTION_REQUEST.name(),
				TOOL_EXECUTION_REQUEST.arguments(),
				event.resultText()
			);

		checkInvocationContext(auditEvent.getInvocationContext());
	}

	@Test
	void mapsInputGuardrailExecutedAuditEvent() {
		InputGuardrail guardrail = new MyInputGuardrail();
		var result = guardrail.validate(INPUT_GUARDRAIL_REQUEST.userMessage());

		var event = InputGuardrailExecutedEvent.builder()
			.invocationContext(INVOCATION_CONTEXT)
			.guardrailClass(guardrail.getClass())
			.request(INPUT_GUARDRAIL_REQUEST)
			.result(result)
			.build();

		var auditEvent = this.auditEventMapper.toAuditEvent(event);

		assertThat(auditEvent)
			.isNotNull()
			.extracting(
				InputGuardrailExecutedAuditEvent::getEventType,
				InputGuardrailExecutedAuditEvent::getUserMessage,
				InputGuardrailExecutedAuditEvent::getRewrittenUserMessage,
				InputGuardrailExecutedAuditEvent::getResult,
				InputGuardrailExecutedAuditEvent::getGuardrailClass
			)
			.containsExactly(
				AuditEventType.INPUT_GUARDRAIL_EXECUTED,
				INPUT_GUARDRAIL_REQUEST.userMessage().singleText(),
				"new text",
				result.result().name(),
				guardrail.getClass().getName()
			);

		checkInvocationContext(auditEvent.getInvocationContext());
	}

	@Test
	void mapsOutputGuardrailExecutedAuditEvent() {
		OutputGuardrail guardrail = new MyOutputGuardrail();
		var result = guardrail.validate(OUTPUT_GUARDRAIL_REQUEST);

		var event = OutputGuardrailExecutedEvent.builder()
			.invocationContext(INVOCATION_CONTEXT)
			.guardrailClass(guardrail.getClass())
			.request(OUTPUT_GUARDRAIL_REQUEST)
			.result(result)
			.build();
		var auditEvent = this.auditEventMapper.toAuditEvent(event);

		assertThat(auditEvent)
			.isNotNull()
			.extracting(
				OutputGuardrailExecutedAuditEvent::getEventType,
				OutputGuardrailExecutedAuditEvent::getResponse,
				OutputGuardrailExecutedAuditEvent::getGuardrailResult,
				OutputGuardrailExecutedAuditEvent::getGuardrailClass
			)
			.containsExactly(
				AuditEventType.OUTPUT_GUARDRAIL_EXECUTED,
				OUTPUT_GUARDRAIL_REQUEST.responseFromLLM().aiMessage().text(),
				result.result().name(),
				guardrail.getClass().getName()
			);

		checkInvocationContext(auditEvent.getInvocationContext());
	}

	private static void checkInvocationContext(InvocationContext invocationContext) {
		assertThat(invocationContext)
			.isNotNull()
			.extracting(
				InvocationContext::getInteractionId,
				InvocationContext::getInterfaceName,
				InvocationContext::getMethodName
			)
			.containsExactly(INVOCATION_ID,
				"someInterface",
				"someMethod"
			);
	}

	private static class MyInputGuardrail implements InputGuardrail {
		@Override
		public InputGuardrailResult validate(UserMessage userMessage) {
			return successWith("new text");
		}
	}

	private static class MyOutputGuardrail implements OutputGuardrail {
		@Override
		public OutputGuardrailResult validate(AiMessage responseFromLLM) {
			return successWith("new text", "new result");
		}
	}
}