package org.parasol.intake.agent.extraction;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailResult;

/**
 * Keeps the summary and sentiment texts at most 5000 characters (the plan's limit for both, PLAN.md → summary and
 * sentiment). A longer answer is reprompted for a shorter one.
 */
@ApplicationScoped
class MaxLengthOutputGuardrail implements OutputGuardrail {
	static final int MAX_LENGTH = 5000;
	static final String TOO_LONG = "Output exceeds %d characters.".formatted(MAX_LENGTH);
	static final String REPROMPT = "Your response exceeded %1$d characters. Please provide a shorter, more concise response under %1$d characters."
		.formatted(MAX_LENGTH);

	@Override
	public OutputGuardrailResult validate(AiMessage responseFromLLM) {
		return Optional.ofNullable(responseFromLLM)
			.map(AiMessage::text)
			.filter(text -> text.length() > MAX_LENGTH)
			.map(_ -> reprompt(TOO_LONG, REPROMPT))
			.orElseGet(this::success);
	}
}
