package ai.scoring.langfuse.evaluation;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.guardrails.JsonExtractorOutputGuardrail;

@ApplicationScoped
public class EvaluatorResultOutputGuardrail extends JsonExtractorOutputGuardrail<EvaluatorResult> {
	public EvaluatorResultOutputGuardrail() {
		super(EvaluatorResult.class);
	}
}
