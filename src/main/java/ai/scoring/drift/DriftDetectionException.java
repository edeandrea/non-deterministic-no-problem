package ai.scoring.drift;

public final class DriftDetectionException extends RuntimeException {
	private final String sampleSetName;
	private final double score;
	private final double threshold;

	private DriftDetectionException(Builder builder) {
		super("Score '%s' for sample '%s' is below threshold of %s".formatted(builder.score, builder.sampleSetName, builder.threshold));
		this.sampleSetName = builder.sampleSetName;
		this.score = builder.score;
		this.threshold = builder.threshold;
	}

	public String getSampleSetName() {
		return sampleSetName;
	}

	public double getScore() {
		return score;
	}

	public double getThreshold() {
		return threshold;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {
		private String sampleSetName;
		private double score;
		private double threshold;

		public Builder sampleSetName(String sampleSetName) {
			this.sampleSetName = sampleSetName;
			return this;
		}

		public Builder score(double score) {
			this.score = score;
			return this;
		}

		public Builder threshold(double threshold) {
			this.threshold = threshold;
			return this;
		}

		public DriftDetectionException build() {
			return new DriftDetectionException(this);
		}
	}
}
