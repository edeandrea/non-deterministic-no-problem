package ai.scoring.langfuse.evaluation;

import java.util.Map;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.guardrail.OutputGuardrails;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.RegisterAiService.NoRetrievalAugmentorSupplier;
import io.quarkiverse.langchain4j.testing.evaluation.EvaluationResult;
import io.quarkiverse.langchain4j.testing.evaluation.EvaluationSample;
import io.quarkiverse.langchain4j.testing.evaluation.EvaluationStrategy;

@RegisterAiService(modelName = "judge", retrievalAugmentor = NoRetrievalAugmentorSupplier.class)
@SystemMessage("""
	You are an AI evaluating a response and the expected output.
	You need to evaluate whether the response is correct or not.
	
	You need to return a verdict (true or false), a score (between 0 and 1), and a reasoning.
	""")
public interface Evaluator extends EvaluationStrategy<String> {
	record EvaluatorResult(boolean verdict, double score, String reasoning) {}

	@UserMessage("""
		Response to evaluate: {response}
		
		Expected output: {expectedOutput}
		""")
	@OutputGuardrails(EvaluatorResultOutputGuardrail.class)
	EvaluatorResult isResponseCorrect(String response, String expectedOutput);

	@Override
	default EvaluationResult evaluate(EvaluationSample<String> sample, String output) {
		var result = isResponseCorrect(output, sample.expectedOutput());

		return new EvaluationResult(result.verdict(), result.score(), result.reasoning(), Map.of());
	}
}
