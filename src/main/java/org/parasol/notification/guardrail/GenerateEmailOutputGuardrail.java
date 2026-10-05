package org.parasol.notification.guardrail;

import org.parasol.notification.ai.GenerateEmailService;
import org.parasol.notification.model.Email;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrails.JsonExtractorOutputGuardrail;

public abstract class GenerateEmailOutputGuardrail extends JsonExtractorOutputGuardrail<Email> {
	public GenerateEmailOutputGuardrail() {
		super(Email.class);
	}

	@Override
	protected String getInvalidJsonMessage(AiMessage aiMessage, String json) {
		return "Invalid JSON format in response";
	}

	@Override
	protected String getInvalidJsonReprompt(AiMessage aiMessage, String json) {
		return GenerateEmailService.JSON_STRUCTURE;
	}
}
