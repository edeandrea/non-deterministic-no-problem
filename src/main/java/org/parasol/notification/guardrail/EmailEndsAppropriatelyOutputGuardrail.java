package org.parasol.notification.guardrail;

import jakarta.enterprise.context.ApplicationScoped;

import org.parasol.notification.ai.GenerateEmailService;

import io.quarkus.qute.Engine;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrailResult;

@ApplicationScoped
public class EmailEndsAppropriatelyOutputGuardrail extends GenerateEmailOutputGuardrail {
	static final String REPROMPT_MESSAGE = "Invalid email body";
	private static final String REPROMPT_PROMPT_TEMPLATE = """
		The email body did not end properly. Please try again.
		
		The email body should end with the following text, EXACTLY as it appears below:
		%s""";

	private final String expectedEnding;
	private final String repromptPrompt;

	// Rendered once: the parasol.claims-department.* values it reads don't change while the app runs.
	// It renders GenerateEmailService.EMAIL_ENDING itself, with the same Qute engine that renders the AI service's
	// system message, so the ending the model is told to write and the one checked here can't drift apart
	EmailEndsAppropriatelyOutputGuardrail(Engine engine) {
		this.expectedEnding = engine.parse(GenerateEmailService.EMAIL_ENDING).render();
		this.repromptPrompt = REPROMPT_PROMPT_TEMPLATE.formatted(this.expectedEnding);
	}

	/**
	 * The text every generated email body must end with, with the claims department's name and phone number filled in.
	 *
	 * @return the rendered {@link GenerateEmailService#EMAIL_ENDING}
	 */
	public String expectedEnding() {
		return this.expectedEnding;
	}

	String repromptPrompt() {
		return this.repromptPrompt;
	}

	@Override
	public OutputGuardrailResult validate(AiMessage responseFromLLM) {
		return extractEmail(responseFromLLM)
			.map(email -> email.body().endsWith(this.expectedEnding) ?
			              success() :
			              reprompt(REPROMPT_MESSAGE, this.repromptPrompt))
			.orElseGet(() -> invalidJson(responseFromLLM));
	}
}
