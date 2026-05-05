package ai.scoring.langfuse.rest.model.request;

import java.util.List;
import java.util.Map;

import ai.scoring.langfuse.rest.model.PricingTierOperator;

public record PricingTierInput(
	String name,
	boolean isDefault,
	int priority,
	List<PricingTierConditionInput> conditions,
	Map<String, Double> prices
) {
	public static Builder builder() {
		return new Builder();
	}

	public Builder toBuilder() {
		return new Builder()
			.name(name)
			.isDefault(isDefault)
			.priority(priority)
			.conditions(conditions)
			.prices(prices);
	}

	public static class Builder {
		private Builder() {}

		private String name;
		private boolean isDefault;
		private int priority;
		private List<PricingTierConditionInput> conditions;
		private Map<String, Double> prices;

		public Builder name(String name) {
			this.name = name;
			return this;
		}

		public Builder isDefault(boolean isDefault) {
			this.isDefault = isDefault;
			return this;
		}

		public Builder priority(int priority) {
			this.priority = priority;
			return this;
		}

		public Builder conditions(List<PricingTierConditionInput> conditions) {
			this.conditions = conditions;
			return this;
		}

		public Builder prices(Map<String, Double> prices) {
			this.prices = prices;
			return this;
		}

		public PricingTierInput build() {
			return new PricingTierInput(name, isDefault, priority, conditions, prices);
		}
	}
}
