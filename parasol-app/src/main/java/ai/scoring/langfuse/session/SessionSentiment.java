package ai.scoring.langfuse.session;

public record SessionSentiment(Sentiment sentiment, String reasoning) {
	public static final String SCORE_NAME = "session-sentiment";

	public enum Sentiment {
		POSITIVE("POSITIVE", 1.0),
		NEUTRAL("NEUTRAL", 0.5),
		NEGATIVE("NEGATIVE", 0.0);

		private final String label;
		private final double value;

		Sentiment(String label, double value) {
			this.label = label;
			this.value = value;
		}

		public String label() {
			return label;
		}

		public double value() {
			return value;
		}
	}
}
