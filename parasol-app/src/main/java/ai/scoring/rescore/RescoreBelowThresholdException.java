package ai.scoring.rescore;

public class RescoreBelowThresholdException extends RuntimeException {
	private final RescoreResult rescoreResult;

	public RescoreBelowThresholdException(RescoreResult rescoreResult, double threshold) {
		super("[Rescoring interaction '%s']: Score %s was below the threshold of %s".formatted(rescoreResult.interactionId(), rescoreResult.score(), threshold));
		this.rescoreResult = rescoreResult;
	}

	public RescoreResult getRescoreResult() {
		return rescoreResult;
	}
}
