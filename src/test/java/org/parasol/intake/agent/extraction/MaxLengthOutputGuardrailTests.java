package org.parasol.intake.agent.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrailResult;

class MaxLengthOutputGuardrailTests {
	private final MaxLengthOutputGuardrail guardrail = new MaxLengthOutputGuardrail();

	@Test
	void exactly5000CharactersPasses() {
		assertThat(this.guardrail.validate(AiMessage.from("a".repeat(5000))))
			.returns(true, OutputGuardrailResult::isSuccess);
	}

	@Test
	void over5000CharactersIsRepromptedForAShorterText() {
		assertThat(this.guardrail.validate(AiMessage.from("a".repeat(5001))))
			.returns(true, OutputGuardrailResult::isReprompt)
			.returns(
				Optional.of("Your response exceeded 5000 characters. Please provide a shorter, more concise response under 5000 characters."),
				OutputGuardrailResult::getReprompt);
	}
}
