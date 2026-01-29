package ai.scoring.evaluation;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import ai.scoring.config.AIScoringConfig;
import ai.scoring.domain.interaction.Interaction;
import ai.scoring.domain.sample.Source;
import io.quarkiverse.langchain4j.testing.evaluation.Evaluation;
import io.quarkiverse.langchain4j.testing.evaluation.EvaluationReport;
import io.quarkiverse.langchain4j.testing.evaluation.EvaluationStrategy;
import io.quarkiverse.langchain4j.testing.evaluation.judge.AiJudgeStrategy;
import io.quarkiverse.langchain4j.testing.evaluation.similarity.SemanticSimilarityStrategy;

import io.quarkus.arc.All;
import io.quarkus.arc.InstanceHandle;

@ApplicationScoped
public class InteractionEvaluator {
	private final EvaluationStrategy<String> evaluationStrategy;

//	public InteractionEvaluator(AIScoringConfig scoringConfig, @Any EvaluationStrategy<String> evaluationStrategy) {
//		this.evaluationStrategy = evaluationStrategy;
//	}

	public InteractionEvaluator(@All List<InstanceHandle<EvaluationStrategy<String>>> evaluationStrategies, AIScoringConfig scoringConfig) {
		var typeToFind = switch (scoringConfig.scoringStrategy()) {
			case AI_JUDGE -> AiJudgeStrategy.class;
			case SEMANTIC_SIMILARITY -> SemanticSimilarityStrategy.class;
		};

		this.evaluationStrategy = evaluationStrategies.stream()
			.filter(instanceHandle -> typeToFind.equals(instanceHandle.getBean().getImplementationClass()))
			.map(InstanceHandle::get)
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("No evaluation strategy found for scoring strategy [%s]".formatted(scoringConfig.scoringStrategy())));
	}

	public EvaluationReport<String> evaluate(Interaction interaction) {
		return Evaluation.<String>builder()
			.withSamples(Source.asSourceString(interaction))
			.evaluate(params -> interaction.getResult())
			.using(this.evaluationStrategy)
			.run();
	}
}
