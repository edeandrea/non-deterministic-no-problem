package ai.scoring.langfuse.evaluation;

public record EvaluatorResult(boolean verdict, double score, String reasoning) {
}
