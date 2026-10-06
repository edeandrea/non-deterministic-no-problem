package org.parasol.notification.guardrail;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrailResult;

@ApplicationScoped
public class PolitenessOutputGuardrail extends GenerateEmailOutputGuardrail {
	static final String REPROMPT_MESSAGE = "Invalid email";
	static final String REPROMPT_PROMPT = "The response was not polite and respectful. Please try again.";

	private final PolitenessService politenessService;

	public PolitenessOutputGuardrail(PolitenessService politenessService) {
		this.politenessService = politenessService;
	}

	@Override
	public OutputGuardrailResult validate(AiMessage responseFromLLM) {
		return extractEmail(responseFromLLM)
			.map(email -> this.politenessService.isPolite(email.body()) ?
			              success() :
			              reprompt(REPROMPT_MESSAGE, REPROMPT_PROMPT))
			.orElseGet(() -> invalidJson(responseFromLLM));
	}
}
