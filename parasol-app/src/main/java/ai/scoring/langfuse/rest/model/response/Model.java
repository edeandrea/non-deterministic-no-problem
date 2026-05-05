package ai.scoring.langfuse.rest.model.response;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import ai.scoring.langfuse.rest.model.ModelUsageUnit;

public record Model(
	String id,
	String modelName,
	String matchPattern,
	Instant startDate,
	ModelUsageUnit unit,
	Double inputPrice,
	Double outputPrice,
	Double totalPrice,
	String tokenizerId,
	Map<String, Object> tokenizerConfig,
	boolean isLangfuseManaged,
	Instant createdAt,
	Map<String, ModelPrice> prices,
	List<PricingTier> pricingTiers
) {
	public static Builder builder() {
		return new Builder();
	}

	public Builder toBuilder() {
		return new Builder()
			.id(id)
			.modelName(modelName)
			.matchPattern(matchPattern)
			.startDate(startDate)
			.unit(unit)
			.inputPrice(inputPrice)
			.outputPrice(outputPrice)
			.totalPrice(totalPrice)
			.tokenizerId(tokenizerId)
			.tokenizerConfig(tokenizerConfig)
			.isLangfuseManaged(isLangfuseManaged)
			.createdAt(createdAt)
			.prices(prices)
			.pricingTiers(pricingTiers);
	}

	public static class Builder {
		private Builder() {}

		private String id;
		private String modelName;
		private String matchPattern;
		private Instant startDate;
		private ModelUsageUnit unit;
		private Double inputPrice;
		private Double outputPrice;
		private Double totalPrice;
		private String tokenizerId;
		private Map<String, Object> tokenizerConfig;
		private boolean isLangfuseManaged;
		private Instant createdAt;
		private Map<String, ModelPrice> prices;
		private List<PricingTier> pricingTiers;

		public Builder id(String id) {
			this.id = id;
			return this;
		}

		public Builder modelName(String modelName) {
			this.modelName = modelName;
			return this;
		}

		public Builder matchPattern(String matchPattern) {
			this.matchPattern = matchPattern;
			return this;
		}

		public Builder startDate(Instant startDate) {
			this.startDate = startDate;
			return this;
		}

		public Builder unit(ModelUsageUnit unit) {
			this.unit = unit;
			return this;
		}

		public Builder inputPrice(Double inputPrice) {
			this.inputPrice = inputPrice;
			return this;
		}

		public Builder outputPrice(Double outputPrice) {
			this.outputPrice = outputPrice;
			return this;
		}

		public Builder totalPrice(Double totalPrice) {
			this.totalPrice = totalPrice;
			return this;
		}

		public Builder tokenizerId(String tokenizerId) {
			this.tokenizerId = tokenizerId;
			return this;
		}

		public Builder tokenizerConfig(Map<String, Object> tokenizerConfig) {
			this.tokenizerConfig = tokenizerConfig;
			return this;
		}

		public Builder isLangfuseManaged(boolean isLangfuseManaged) {
			this.isLangfuseManaged = isLangfuseManaged;
			return this;
		}

		public Builder createdAt(Instant createdAt) {
			this.createdAt = createdAt;
			return this;
		}

		public Builder prices(Map<String, ModelPrice> prices) {
			this.prices = prices;
			return this;
		}

		public Builder pricingTiers(List<PricingTier> pricingTiers) {
			this.pricingTiers = pricingTiers;
			return this;
		}

		public Model build() {
			return new Model(
				id, modelName, matchPattern, startDate, unit, inputPrice, outputPrice,
				totalPrice, tokenizerId, tokenizerConfig, isLangfuseManaged, createdAt,
				prices, pricingTiers
			);
		}
	}
}
