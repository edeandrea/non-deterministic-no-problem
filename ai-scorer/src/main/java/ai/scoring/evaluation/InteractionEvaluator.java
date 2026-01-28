package ai.scoring.evaluation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import ai.scoring.domain.interaction.Interaction;
import ai.scoring.domain.sample.Source;
import io.quarkiverse.langchain4j.testing.evaluation.Evaluation;
import io.quarkiverse.langchain4j.testing.evaluation.EvaluationReport;
import io.quarkiverse.langchain4j.testing.evaluation.EvaluationStrategy;

@ApplicationScoped
public class InteractionEvaluator {
	private final EvaluationStrategy<String> evaluationStrategy;

	public InteractionEvaluator(Instance<EvaluationStrategy<String>> evaluationStrategy) {
		this.evaluationStrategy = evaluationStrategy.get();
	}

	public EvaluationReport<String> evaluate(Interaction interaction) {
		return Evaluation.<String>builder()
			.withSamples(Source.asSourceString(interaction))
			.evaluate(params -> interaction.getResult())
			.using(this.evaluationStrategy)
			.run();
	}
}
