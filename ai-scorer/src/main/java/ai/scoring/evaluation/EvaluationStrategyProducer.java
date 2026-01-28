package ai.scoring.evaluation;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.CDI;

import ai.scoring.config.AIScoringConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkiverse.langchain4j.testing.evaluation.EvaluationStrategy;
import io.quarkiverse.langchain4j.testing.evaluation.judge.AiJudgeStrategy;
import io.quarkiverse.langchain4j.testing.evaluation.similarity.SemanticSimilarityStrategy;

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
	public EvaluationStrategy<String> evaluationStrategy(AIScoringConfig scoringConfig) {
		return switch(scoringConfig.scoringStrategy()) {
			case AI_JUDGE -> new AiJudgeStrategy(
				getModel(scoringConfig.aiJudge().modelConfigName(), ChatModel.class),
				PROMPT
			);

			case SEMANTIC_SIMILARITY -> new SemanticSimilarityStrategy(
				getModel(scoringConfig.semanticSimilarity().modelConfigName(), EmbeddingModel.class),
				scoringConfig.semanticSimilarity().threshold()
			);
		};
	}


	private <T> T getModel(Optional<String> modelConfigName, Class<T> clazz) {
		var cdi = CDI.current();

		return modelConfigName
			.map(mcn -> cdi.select(clazz, ModelName.Literal.of(mcn)))
			.orElseGet(() -> cdi.select(clazz))
			.get();
	}
}
