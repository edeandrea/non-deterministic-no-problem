package org.parasol.notification.guardrail;

import jakarta.enterprise.context.ApplicationScoped;

import org.parasol.notification.ai.GenerateEmailService;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrailResult;

@ApplicationScoped
public class EmailEndsAppropriatelyOutputGuardrail extends GenerateEmailOutputGuardrail {
	static final String REPROMPT_MESSAGE = "Invalid email body";
	static final String REPROMPT_PROMPT = """
		The email body did not end properly. Please try again.
		
		The email body should end with the following text, EXACTLY as it appears below:
		""" + GenerateEmailService.EMAIL_ENDING;

	@Override
	public OutputGuardrailResult validate(AiMessage responseFromLLM) {
		return extractEmail(responseFromLLM)
			.map(email -> email.body().endsWith(GenerateEmailService.EMAIL_ENDING) ?
			              success() :
			              reprompt(REPROMPT_MESSAGE, REPROMPT_PROMPT))
			.orElseGet(() -> invalidJson(responseFromLLM));
	}
}
