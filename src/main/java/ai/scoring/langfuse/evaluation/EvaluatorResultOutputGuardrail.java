package ai.scoring.langfuse.evaluation;

import jakarta.enterprise.context.ApplicationScoped;

import ai.scoring.langfuse.evaluation.Evaluator.EvaluatorResult;
import dev.langchain4j.guardrails.JsonExtractorOutputGuardrail;

@ApplicationScoped
public class EvaluatorResultOutputGuardrail extends JsonExtractorOutputGuardrail<EvaluatorResult> {
	public EvaluatorResultOutputGuardrail() {
		super(EvaluatorResult.class);
	}
}
