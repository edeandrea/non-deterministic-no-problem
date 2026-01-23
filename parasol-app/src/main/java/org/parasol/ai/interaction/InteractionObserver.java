package org.parasol.ai.interaction;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.observability.api.event.AiServiceCompletedEvent;
import dev.langchain4j.observability.api.event.AiServiceErrorEvent;
import dev.langchain4j.observability.api.event.AiServiceResponseReceivedEvent;
import dev.langchain4j.observability.api.event.AiServiceStartedEvent;
import dev.langchain4j.observability.api.event.InputGuardrailExecutedEvent;
import dev.langchain4j.observability.api.event.OutputGuardrailExecutedEvent;
import dev.langchain4j.observability.api.event.ToolExecutedEvent;

import io.quarkus.logging.Log;

@ApplicationScoped
public class InteractionObserver {
	@InteractionObserved(
		name = "parasol.llm.interaction.started",
		description = "A count of LLM services started",
		unit = "service interactions started"
	)
	public void serviceStarted(@Observes AiServiceStartedEvent e) {
		Log.infof(
			"LLM service started event:\ncontext: %s\nsystemMessage: %s\nuserMessage: %s",
			e.invocationContext(),
			e.systemMessage().map(SystemMessage::text).orElse(""),
			e.userMessage().singleText()
		);
	}

	@InteractionObserved(
		name = "parasol.llm.interaction.completed",
		description = "A count of LLM interactions completed",
		unit = "completed interactions"
	)
	public void serviceCompleted(@Observes AiServiceCompletedEvent e) {
		Log.infof(
			"LLM interaction complete:\ncontext: %s\nresult: %s",
			e.invocationContext(),
			e.result()
		);
	}

	@InteractionObserved(
		name = "parasol.llm.interaction.failed",
		description = "A count of LLM interactions failed",
		unit = "failed interactions"
	)
	public void serviceFailed(@Observes AiServiceErrorEvent e) {
		Log.infof(
			"LLM interaction failed:\ncontext: %s\nfailure: %s",
			e.invocationContext(),
			e.error().getMessage()
		);
	}

	@InteractionObserved(
		name = "parasol.llm.response.received",
		description = "A count of LLM responses received",
		unit = "received responses"
	)
	public void responseReceived(@Observes AiServiceResponseReceivedEvent e) {
		Log.infof(
			"Response from LLM received:\ncontext: %s\nresponse: %s",
			e.invocationContext(),
			e.response().aiMessage().text()
		);
	}

	@InteractionObserved(
		name = "parasol.llm.tool.executed",
		description = "A count of tools executed",
		unit = "executed tools"
	)
	public void toolExecuted(@Observes ToolExecutedEvent e) {
		Log.infof(
			"Tool executed:\ncontext: %s\nrequest: %s(%s)\nresult: %s",
			e.invocationContext(),
			e.request().name(),
			e.request().arguments(),
			e.resultText()
		);
	}

	@InteractionObserved(
		name = "parasol.llm.guardrail.input.executed",
		description = "A count of input guardrails executed",
		unit = "executed input guardrails"
	)
	public void inputGuardrailExecuted(@Observes InputGuardrailExecutedEvent e) {
		Log.infof(
			"Input guardrail executed:\nuserMessage: %s\nresult: %s",
			e.rewrittenUserMessage().singleText(),
			e.result().result()
		);
	}

	@InteractionObserved(
		name = "parasol.llm.guardrail.output.executed",
		description = "A count of output guardrails executed",
		unit = "executed output guardrails"
	)
	public void outputGuardrailExecuted(@Observes OutputGuardrailExecutedEvent e) {
		Log.infof("Output guardrail executed:\nresponseFromLLM:%s\nresult: %s",
			e.request().responseFromLLM().aiMessage().text(),
			e.result().result()
		);
	}
}
