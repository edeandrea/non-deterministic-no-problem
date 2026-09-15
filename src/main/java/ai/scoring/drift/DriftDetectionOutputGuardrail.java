package ai.scoring.drift;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.logging.Log;

import ai.scoring.config.InteractionMode;
import ai.scoring.config.ScoringConfig;
import ai.scoring.langfuse.otel.AiServiceAttributes;
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
				var sampleSetName = getAIServiceName(invocationContext);
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

	/**
	 * The dataset name is the LangChain4j AI service span name, verbatim:
	 * {@code langchain4j.aiservices.<AiServiceClassName>.<methodName>}.
	 * <p>
	 * This must match what the session scorer records (see {@code ConversationExchange}) or drift detection will never
	 * find any samples. Both sides share {@link AiServiceAttributes#AI_SERVICES_PREFIX} to keep them in lock-step.
	 */
	private static String getAIServiceName(InvocationContext invocationContext) {
		// interfaceName() is fully-qualified; Quarkus LangChain4j names the span with the simple class name
		return "%s%s.%s".formatted(AiServiceAttributes.AI_SERVICES_PREFIX, getAIServiceClassName(invocationContext), invocationContext.methodName());
	}
}
