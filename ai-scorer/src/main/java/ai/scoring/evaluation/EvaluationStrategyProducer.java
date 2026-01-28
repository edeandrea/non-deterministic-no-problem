package ai.scoring.evaluation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import dev.langchain4j.model.chat.ChatModel;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkiverse.langchain4j.testing.evaluation.EvaluationStrategy;
import io.quarkiverse.langchain4j.testing.evaluation.judge.AiJudgeStrategy;

public class EvaluationStrategyProducer {
	public static final String PROMPT = """
		You are an AI evaluating a response and the expected output.
		You need to evaluate whether the response is correct or not.
		Return true if the response is correct, false otherwise.
		
		Response to evaluate: {response}
		Expected output: {expected_output}
		
		""";

	@Produces
	@ApplicationScoped
	public EvaluationStrategy<String> evaluationStrategy(@ModelName("judge") ChatModel chatModel) {
		return new AiJudgeStrategy(chatModel, PROMPT);
	}
}
