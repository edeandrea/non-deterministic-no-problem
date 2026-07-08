package ai.scoring.langfuse.evaluation;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkiverse.langchain4j.testing.evaluation.EvaluationResult;
import io.quarkiverse.langchain4j.testing.evaluation.EvaluationSample;
import io.quarkiverse.langchain4j.testing.evaluation.EvaluationStrategy;

@ApplicationScoped
public class Evaluator implements EvaluationStrategy<String> {
	private final EvaluatorAgent evaluatorAgent;

	public Evaluator(EvaluatorAgent evaluatorAgent) {
		this.evaluatorAgent = evaluatorAgent;
	}

	@Override
	public EvaluationResult evaluate(EvaluationSample<String> sample, String output) {
		var result = this.evaluatorAgent.isResponseCorrect(sample.parameters().get("input"), output, sample.expectedOutput());

		return new EvaluationResult(result.verdict(), result.score(), result.reasoning(), Map.of());
	}
}
