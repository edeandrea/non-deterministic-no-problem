package org.parasol.ai.guardrail;

import org.parasol.ai.Email;
import org.parasol.ai.GenerateEmailService;

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
