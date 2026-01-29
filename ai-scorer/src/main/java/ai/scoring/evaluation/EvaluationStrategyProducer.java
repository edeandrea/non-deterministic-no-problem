package ai.scoring.evaluation;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import ai.scoring.config.AIScoringConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkiverse.langchain4j.testing.evaluation.judge.AiJudgeStrategy;
import io.quarkiverse.langchain4j.testing.evaluation.similarity.SemanticSimilarityStrategy;

import io.quarkus.arc.Arc;

public class EvaluationStrategyProducer {
	private final AIScoringConfig scoringConfig;

	public static final String PROMPT = """
		You are an AI evaluating a response and the expected output.
		You need to evaluate whether the response is correct or not.
		Return true if the response is correct, false otherwise.
		
		Response to evaluate: {response}
		Expected output: {expected_output}
		
		""";

	public EvaluationStrategyProducer(AIScoringConfig scoringConfig) {
		this.scoringConfig = scoringConfig;
	}

	@Produces
	@ApplicationScoped
//	@IfBuildProperty(name = "ai.scoring.scoring-strategy", stringValue = "ai-judge", enableIfMissing = true)
	public AiJudgeStrategy aiJudgeStrategy(AIScoringConfig scoringConfig) {
		return new AiJudgeStrategy(
			getModel(ChatModel.class, scoringConfig.aiJudge().modelConfigName()),
			PROMPT
		);
	}

	@Produces
	@ApplicationScoped
//	@IfBuildProperty(name = "ai.scoring.scoring-strategy", stringValue = "semantic-similarity")
	public SemanticSimilarityStrategy semanticSimilarityStrategy(AIScoringConfig scoringConfig) {
		return new SemanticSimilarityStrategy(
			getModel(EmbeddingModel.class, scoringConfig.semanticSimilarity().modelConfigName()),
			scoringConfig.semanticSimilarity().threshold()
		);
	}

	private static <T> T getModel(Class<T> modelClass, Optional<String> modelName) {
//		return modelName
//			.map(mcn -> CDI.current().select(modelClass, ModelName.Literal.of(mcn)))
//			.orElseGet(() -> CDI.current().select(modelClass))
//			.get();
		return modelName
			.map(mcn -> Arc.container().instance(modelClass, ModelName.Literal.of(mcn)))
			.orElseGet(() -> Arc.container().instance(modelClass))
			.get();
	}
}
