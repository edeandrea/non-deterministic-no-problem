package org.parasol.ai.guardrail;

import static dev.langchain4j.test.guardrail.GuardrailAssertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.GuardrailResult.Result;

@QuarkusTest
class PolitenessOutputGuardrailTests {
	private static final String JSON = "{\"subject\": \"This is a subject\", \"body\": \"%s\"}";

	@InjectMock
	PolitenessService politenessService;

	@InjectSpy
	PolitenessOutputGuardrail guardrail;

	@Test
	void guardrailSuccess() {
		var body = "This is a polite response";;
		var aiMessage = AiMessage.from(JSON.formatted(body));

		when(this.politenessService.isPolite(body))
			.thenReturn(true);

		assertThat(this.guardrail.validate(aiMessage))
			.isSuccessful();

		verify(this.politenessService).isPolite(body);
		verifyNoMoreInteractions(this.politenessService);
	}

	@Test
	void emailIsntPolite() {
		var body = "Hello. Claim number 1234 has been approved.";
		var aiMessage = AiMessage.from(JSON.formatted(body));

		when(this.politenessService.isPolite(body))
			.thenReturn(false);

		var guardrailResult = this.guardrail.validate(aiMessage);

		assertThat(guardrailResult)
			.hasResult(Result.FATAL)
			.hasSingleFailureWithMessage(PolitenessOutputGuardrail.REPROMPT_MESSAGE);

		verify(this.guardrail).reprompt(PolitenessOutputGuardrail.REPROMPT_MESSAGE, PolitenessOutputGuardrail.REPROMPT_PROMPT);
		verify(this.politenessService).isPolite(body);
		verifyNoMoreInteractions(this.politenessService);
	}
}