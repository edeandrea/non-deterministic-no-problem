package ai.scoring.drift;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.logging.Log;

import ai.scoring.config.InteractionMode;
import ai.scoring.config.ScoringConfig;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import dev.langchain4j.invocation.InvocationContext;
import io.quarkiverse.langchain4j.testing.evaluation.Evaluation;
import io.quarkiverse.langchain4j.testing.evaluation.EvaluationStrategy;
import io.quarkiverse.langchain4j.testing.evaluation.SampleLoadException;

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
				var sampleSetName = "langchain4j.aiservices.%s".formatted(getAIServiceName(invocationContext));
				var evaluation = Evaluation.<String>builder()
				                           .withConcurrency(Math.clamp(numCpus - 2, 1, numCpus))
				                           .withSamples(sampleSetName)
				                           .evaluate(params -> request.responseFromLLM().aiMessage().text())
				                           .using(this.evaluationStrategy)
				                           .run();

				Log.debugf("Score for sample '%s' == %s", sampleSetName, evaluation.score());
				var score = evaluation.score() / 100.0;

				return (score < this.scoringConfig.threshold()) ?
				       fatal(
								 "Score [%s] for sample '%s' is below threshold of %s".formatted(score, sampleSetName, this.scoringConfig.threshold()),
					       DriftDetectionException.builder()
					                              .sampleSetName(sampleSetName)
					                              .score(score)
					                              .threshold(this.scoringConfig.threshold())
					                              .build()) :
				       success();
			}
			catch (SampleLoadException ex) {
				return successWith(ex.getMessage());
			}
		}

		return success();
	}

	private static String getAIServiceClassName(InvocationContext invocationContext) {
		return invocationContext.interfaceName().substring(invocationContext.interfaceName().lastIndexOf('.') + 1);
	}

	private static String getAIServiceName(InvocationContext invocationContext) {
		return "%s.%s".formatted(getAIServiceClassName(invocationContext), invocationContext.methodName());
	}
}
