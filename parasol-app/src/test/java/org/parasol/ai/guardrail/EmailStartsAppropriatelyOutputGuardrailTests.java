package org.parasol.ai.guardrail;

import static dev.langchain4j.test.guardrail.GuardrailAssertions.assertThat;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.GuardrailResult.Result;

@QuarkusTest
class EmailStartsAppropriatelyOutputGuardrailTests {
	private static final String JSON = "{\"subject\":\"This is a subject\",\"body\":\"%s\"}";

	@InjectSpy
	EmailStartsAppropriatelyOutputGuardrail guardrail;

	@Test
	void guardrailSuccess() {
		var aiMessage = AiMessage.from(JSON.formatted("Dear John,"));

		assertThat(this.guardrail.validate(aiMessage))
			.isSuccessful();
	}

	@Test
	void emailDoesntStartAppropriately() {
		var aiMessage = AiMessage.from(JSON.formatted("Hello there."));
		var guardrailResult = this.guardrail.validate(aiMessage);

		assertThat(guardrailResult)
			.hasResult(Result.FATAL)
			.hasSingleFailureWithMessage(EmailStartsAppropriatelyOutputGuardrail.REPROMPT_MESSAGE);

		verify(this.guardrail).reprompt(EmailStartsAppropriatelyOutputGuardrail.REPROMPT_MESSAGE, EmailStartsAppropriatelyOutputGuardrail.REPROMPT_PROMPT);
	}
}