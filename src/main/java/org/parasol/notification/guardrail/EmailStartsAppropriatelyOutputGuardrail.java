package org.parasol.notification.guardrail;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrailResult;

@ApplicationScoped
public class EmailStartsAppropriatelyOutputGuardrail extends GenerateEmailOutputGuardrail {
	static final String REPROMPT_MESSAGE = "Invalid email body";
	static final String REPROMPT_PROMPT = "The email body did not start with 'Dear'. Please try again.";

	@Override
	public OutputGuardrailResult validate(AiMessage responseFromLLM) {
		return extractEmail(responseFromLLM)
			.map(email -> email.body().startsWith("Dear ") ?
			              success() :
			              reprompt(REPROMPT_MESSAGE, REPROMPT_PROMPT))
			.orElseGet(() -> invalidJson(responseFromLLM));
	}
}
