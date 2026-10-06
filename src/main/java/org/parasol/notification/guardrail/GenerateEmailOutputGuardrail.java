package org.parasol.notification.guardrail;

import java.util.Optional;

import org.parasol.notification.ai.GenerateEmailService;
import org.parasol.notification.model.Email;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import dev.langchain4j.guardrails.JsonExtractorOutputGuardrail;

/**
 * Base for the content checks on a generated {@link Email}.
 * <p>
 * It extends {@link JsonExtractorOutputGuardrail} only to reuse {@link #deserialize(String)}. Subclasses must
 * <b>not</b> call that class's {@code validate}: it reports success by <b>rewriting</b> the output, and once any
 * guardrail in a chain has rewritten, {@code OutputGuardrailExecutor} refuses every later retry or reprompt and
 * turns it into "Retry or reprompt is not allowed after a rewritten output". Return {@link #success()} or
 * {@link #reprompt(String, String)} instead - never {@code successWith(...)}.
 * <p>
 * Nothing here needs to rewrite: Quarkus and LangChain4j already extract JSON from surrounding prose on the AI
 * service return path ({@code PojoOutputParser} -&gt; {@code JsonParsingUtils.extractAndParseJson}, plus
 * {@code QuarkusJsonCodecFactory}'s own sanitizing regex). What this class still adds is the deliberately strict
 * parse below and a reprompt, rather than an escaping parse exception, when the model's output can't be read.
 *
 * @see <a href="https://github.com/edeandrea/non-deterministic-no-problem/issues/228">#228</a>
 */
public abstract class GenerateEmailOutputGuardrail extends JsonExtractorOutputGuardrail<Email> {
	protected GenerateEmailOutputGuardrail() {
		super(Email.class);
	}

	/**
	 * The email the model produced, or empty when the response holds no parseable {@link Email}.
	 */
	protected Optional<Email> extractEmail(AiMessage responseFromLLM) {
		return deserialize(responseFromLLM.text())
			.map(parsed -> parsed.value());
	}

	/**
	 * Reprompts for output that could not be parsed into an {@link Email}.
	 */
	protected OutputGuardrailResult invalidJson(AiMessage responseFromLLM) {
		var text = responseFromLLM.text();

		return reprompt(getInvalidJsonMessage(responseFromLLM, text), getInvalidJsonReprompt(responseFromLLM, text));
	}

	@Override
	protected String getInvalidJsonMessage(AiMessage aiMessage, String json) {
		return "Invalid JSON format in response";
	}

	@Override
	protected String getInvalidJsonReprompt(AiMessage aiMessage, String json) {
		return GenerateEmailService.JSON_STRUCTURE;
	}
}
