package org.parasol.ai.guardrail;

import static dev.langchain4j.test.guardrail.GuardrailAssertions.assertThat;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.parasol.ai.GenerateEmailService;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.GuardrailResult.Result;

@QuarkusTest
class EmailEndsAppropriatelyOutputGuardrailTests {
	private static final String JSON = "{\"subject\":\"This is a subject\",\"body\":\"%s\"}";

	@InjectSpy
	EmailEndsAppropriatelyOutputGuardrail guardrail;

	@Test
	void guardrailSuccess() {
		var text = """
			Hello. Claim number 1234 has been approved.
			
			""" + GenerateEmailService.EMAIL_ENDING;
		var json = JSON.formatted(text).replaceAll("\n", "\\\\n");
		var aiMessage = AiMessage.from(json);

		assertThat(this.guardrail.validate(aiMessage))
			.isSuccessful();
	}

	@Test
	void emailDoesntEndAppropriately() {
		var json = JSON.formatted("Hello. Claim number 1234 has been approved.");
		var aiMessage = AiMessage.from(json);
		var guardrailResult = this.guardrail.validate(aiMessage);

		assertThat(guardrailResult)
			.hasResult(Result.FATAL)
			.hasSingleFailureWithMessage(EmailEndsAppropriatelyOutputGuardrail.REPROMPT_MESSAGE);

		verify(this.guardrail).reprompt(EmailEndsAppropriatelyOutputGuardrail.REPROMPT_MESSAGE, EmailEndsAppropriatelyOutputGuardrail.REPROMPT_PROMPT);
	}
}