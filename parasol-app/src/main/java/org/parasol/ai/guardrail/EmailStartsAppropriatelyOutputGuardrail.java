package org.parasol.ai.guardrail;

import jakarta.enterprise.context.ApplicationScoped;

import org.parasol.ai.Email;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrailResult;

@ApplicationScoped
public class EmailStartsAppropriatelyOutputGuardrail extends GenerateEmailOutputGuardrail {
	static final String REPROMPT_MESSAGE = "Invalid email body";
	static final String REPROMPT_PROMPT = "The email body did not start with 'Dear'. Please try again.";

	@Override
	public OutputGuardrailResult validate(AiMessage responseFromLLM) {
		var result = super.validate(responseFromLLM);

		if (result.isSuccess()) {
			var email = (Email) result.successfulResult();

			return email.body().startsWith("Dear ") ?
			       result :
			       reprompt(REPROMPT_MESSAGE, REPROMPT_PROMPT);
		}

		return result;
	}
}
