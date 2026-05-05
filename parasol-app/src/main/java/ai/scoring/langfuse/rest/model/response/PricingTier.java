package ai.scoring.langfuse.rest.model.response;

import java.util.List;
import java.util.Map;

import ai.scoring.langfuse.rest.model.PricingTierOperator;

public record PricingTier(
	String id,
	String name,
	boolean isDefault,
	int priority,
	List<PricingTierCondition> conditions,
	Map<String, Double> prices
) {
	public static Builder builder() {
		return new Builder();
	}

	public Builder toBuilder() {
		return new Builder()
			.id(id)
			.name(name)
			.isDefault(isDefault)
			.priority(priority)
			.conditions(conditions)
			.prices(prices);
	}

	public static class Builder {
		private Builder() {}

		private String id;
		private String name;
		private boolean isDefault;
		private int priority;
		private List<PricingTierCondition> conditions;
		private Map<String, Double> prices;

		public Builder id(String id) {
			this.id = id;
			return this;
		}

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

		public Builder conditions(List<PricingTierCondition> conditions) {
			this.conditions = conditions;
			return this;
		}

		public Builder prices(Map<String, Double> prices) {
			this.prices = prices;
			return this;
		}

		public PricingTier build() {
			return new PricingTier(id, name, isDefault, priority, conditions, prices);
		}
	}
}
