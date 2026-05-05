package ai.scoring.langfuse.rest.model.response;

import ai.scoring.langfuse.rest.model.PricingTierOperator;

public record PricingTierCondition(
	String usageDetailPattern,
	PricingTierOperator operator,
	double value,
	boolean caseSensitive
) {
	public static Builder builder() {
		return new Builder();
	}

	public Builder toBuilder() {
		return new Builder()
			.usageDetailPattern(usageDetailPattern)
			.operator(operator)
			.value(value)
			.caseSensitive(caseSensitive);
	}

	public static class Builder {
		private Builder() {}

		private String usageDetailPattern;
		private PricingTierOperator operator;
		private double value;
		private boolean caseSensitive;

		public Builder usageDetailPattern(String usageDetailPattern) {
			this.usageDetailPattern = usageDetailPattern;
			return this;
		}

		public Builder operator(PricingTierOperator operator) {
			this.operator = operator;
			return this;
		}

		public Builder value(double value) {
			this.value = value;
			return this;
		}

		public Builder caseSensitive(boolean caseSensitive) {
			this.caseSensitive = caseSensitive;
			return this;
		}

		public PricingTierCondition build() {
			return new PricingTierCondition(usageDetailPattern, operator, value, caseSensitive);
		}
	}
}
