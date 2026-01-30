package ai.scoring.domain.interaction;

import io.quarkiverse.langchain4j.testing.evaluation.EvaluationReport;

public record RescoreResult(InteractionScore interactionScore, EvaluationReport<String> evaluationReport) {
}
