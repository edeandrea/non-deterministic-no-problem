package ai.scoring.evaluation;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import ai.scoring.config.AIScoringConfig;
import ai.scoring.domain.interaction.Interaction;
import ai.scoring.domain.sample.Source;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkiverse.langchain4j.testing.evaluation.Evaluation;
import io.quarkiverse.langchain4j.testing.evaluation.EvaluationReport;
import io.quarkiverse.langchain4j.testing.evaluation.EvaluationStrategy;
import io.quarkiverse.langchain4j.testing.evaluation.judge.AiJudgeStrategy;
import io.quarkiverse.langchain4j.testing.evaluation.similarity.SemanticSimilarityStrategy;

import io.quarkus.arc.Arc;

@ApplicationScoped
public class InteractionEvaluator {
	private static final String PROMPT = """
		You are an AI evaluating a response and the expected output.
		You need to evaluate whether the response is correct or not.
		Return true if the response is correct, false otherwise.
		
		Response to evaluate: {response}
		Expected output: {expected_output}
		
		""";

	private final EvaluationStrategy<String> evaluationStrategy;

	public InteractionEvaluator(AIScoringConfig scoringConfig, @ModelName("judge") ChatModel chatModel) {
		this.evaluationStrategy = switch (scoringConfig.scoringStrategy()) {
			case AI_JUDGE -> new AiJudgeStrategy(chatModel, PROMPT);
			case SEMANTIC_SIMILARITY -> new SemanticSimilarityStrategy(getModel(EmbeddingModel.class, scoringConfig.semanticSimilarity().modelConfigName()), scoringConfig.semanticSimilarity().threshold());
		};
	}

	//	public InteractionEvaluator(AIScoringConfig scoringConfig) {
//		this.evaluationStrategy = switch (scoringConfig.scoringStrategy()) {
//			case AI_JUDGE -> new AiJudgeStrategy(getModel(ChatModel.class, scoringConfig.aiJudge().modelConfigName()), PROMPT);
//			case SEMANTIC_SIMILARITY -> new SemanticSimilarityStrategy(getModel(EmbeddingModel.class, scoringConfig.semanticSimilarity().modelConfigName()), scoringConfig.semanticSimilarity().threshold());
//		};
//	}

//	private EvaluationStrategy<String> calculateEvaluationStrategy() {
//		return switch (scoringConfig.scoringStrategy()) {
//			case AI_JUDGE -> new AiJudgeStrategy(getModel(ChatModel.class, scoringConfig.aiJudge().modelConfigName()), PROMPT);
//			case SEMANTIC_SIMILARITY -> new SemanticSimilarityStrategy(getModel(EmbeddingModel.class, scoringConfig.semanticSimilarity().modelConfigName()), scoringConfig.semanticSimilarity().threshold());
//		};
//	}

	private static <T> T getModel(Class<T> modelClass, Optional<String> modelName) {
		var model = modelName
			.map(mcn -> Arc.container().instance(modelClass, ModelName.Literal.of(mcn)))
			.orElseGet(() -> Arc.container().instance(modelClass))
			.get();

		return model;
	}

//	public InteractionEvaluator(AIScoringConfig scoringConfig, @Any EvaluationStrategy<String> evaluationStrategy) {
//		this.evaluationStrategy = evaluationStrategy;
//	}

//	public InteractionEvaluator(@All List<InstanceHandle<EvaluationStrategy<String>>> evaluationStrategies, AIScoringConfig scoringConfig) {
//		var typeToFind = switch (scoringConfig.scoringStrategy()) {
//			case AI_JUDGE -> AiJudgeStrategy.class;
//			case SEMANTIC_SIMILARITY -> SemanticSimilarityStrategy.class;
//		};
//
//		this.evaluationStrategy = evaluationStrategies.stream()
//			.filter(instanceHandle -> typeToFind.equals(instanceHandle.getBean().getImplementationClass()))
//			.map(InstanceHandle::get)
//			.findFirst()
//			.orElseThrow(() -> new IllegalStateException("No evaluation strategy found for scoring strategy [%s]".formatted(scoringConfig.scoringStrategy())));
//	}

	public EvaluationReport<String> evaluate(Interaction interaction) {
//		this.evaluationStrategy.compareAndSet(null, calculateEvaluationStrategy());

		return Evaluation.<String>builder()
			.withSamples(Source.asSourceString(interaction))
			.evaluate(params -> interaction.getResult())
			.using(this.evaluationStrategy)
			.run();
	}
}
