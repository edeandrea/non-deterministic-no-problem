package org.ericoleg.ndnp.ai.guardrail;

import static io.quarkiverse.langchain4j.guardrails.GuardrailAssertions.assertThat;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.ericoleg.ndnp.ai.GenerateEmailService;
import org.junit.jupiter.api.Test;

import dev.langchain4j.data.message.AiMessage;
import io.quarkiverse.langchain4j.guardrails.GuardrailResult.Result;

class EmailEndsAppropriatelyOutputGuardrailTests {
	EmailEndsAppropriatelyOutputGuardrail guardrail = spy(new EmailEndsAppropriatelyOutputGuardrail());

	@Test
	void guardrailSuccess() {
		var text = """
			Hello. Claim number 1234 has been approved.
			
			""" + GenerateEmailService.EMAIL_ENDING;

		var aiMessage = AiMessage.from(text);

		assertThat(this.guardrail.validate(aiMessage))
			.isSuccessful();

		verify(this.guardrail).validate(aiMessage);
		verify(this.guardrail).success();
		verifyNoMoreInteractions(this.guardrail);
	}

	@Test
	void emailDoesntEndAppropriately() {
		var aiMessage = AiMessage.from("Hello. Claim number 1234 has been approved.");
		var guardrailResult = this.guardrail.validate(aiMessage);

		assertThat(guardrailResult)
			.hasResult(Result.FATAL)
			.hasSingleFailureWithMessage(EmailEndsAppropriatelyOutputGuardrail.REPROMPT_MESSAGE);

		verify(this.guardrail).validate(aiMessage);
		verify(this.guardrail).reprompt(EmailEndsAppropriatelyOutputGuardrail.REPROMPT_MESSAGE, EmailEndsAppropriatelyOutputGuardrail.REPROMPT_PROMPT);
		verifyNoMoreInteractions(this.guardrail);
	}
}