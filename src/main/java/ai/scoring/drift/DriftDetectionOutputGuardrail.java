package ai.scoring.drift;

import jakarta.enterprise.context.ApplicationScoped;

import ai.scoring.config.InteractionMode;
import ai.scoring.config.ScoringConfig;
import io.quarkiverse.langchain4j.testing.evaluation.Evaluation;
import io.quarkiverse.langchain4j.testing.evaluation.EvaluationStrategy;
import io.quarkiverse.langchain4j.testing.evaluation.SampleLoadException;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.guardrail.OutputGuardrailResult;

@ApplicationScoped
public class DriftDetectionOutputGuardrail implements OutputGuardrail {
	private final ScoringConfig scoringConfig;
	private final EvaluationStrategy<String> evaluationStrategy;

	public DriftDetectionOutputGuardrail(ScoringConfig scoringConfig, EvaluationStrategy<String> evaluationStrategy) {
		this.scoringConfig = scoringConfig;
		this.evaluationStrategy = evaluationStrategy;
	}

	@Override
	public OutputGuardrailResult validate(OutputGuardrailRequest request) {
		if (this.scoringConfig.interactionMode() == InteractionMode.DRIFT_DETECTION) {
			var invocationContext = request.requestParams().invocationContext();
			var numCpus = Runtime.getRuntime().availableProcessors();

			try {
				var evaluation = Evaluation.<String>builder()
				                           .withConcurrency(Math.clamp(numCpus - 2, 1, numCpus))
				                           .withSamples("%s.%s".formatted(invocationContext.interfaceName(), invocationContext.methodName()))
				                           .evaluate(params -> request.responseFromLLM().aiMessage().text())
				                           .using(this.evaluationStrategy)
				                           .run();

				return (evaluation.score() < this.scoringConfig.threshold()) ?
				       failure("Score is below threshold of %s".formatted(this.scoringConfig.threshold())) :
				       success();
			}
			catch (SampleLoadException ex) {
				return successWith(ex.getMessage());
			}
		}

		return success();
	}
}
