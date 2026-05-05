package ai.scoring.langfuse.rest.model.request;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import ai.scoring.langfuse.rest.model.ModelUsageUnit;

public record CreateModelRequest(
	String modelName,
	String matchPattern,
	ModelUsageUnit unit,
	Double inputPrice,
	Double outputPrice,
	Double totalPrice,
	String tokenizerId,
	Map<String, Object> tokenizerConfig,
	Instant startDate,
	List<PricingTierInput> pricingTiers
) {
	public static Builder builder() {
		return new Builder();
	}

	public Builder toBuilder() {
		return new Builder()
			.modelName(modelName)
			.matchPattern(matchPattern)
			.unit(unit)
			.inputPrice(inputPrice)
			.outputPrice(outputPrice)
			.totalPrice(totalPrice)
			.tokenizerId(tokenizerId)
			.tokenizerConfig(tokenizerConfig)
			.startDate(startDate)
			.pricingTiers(pricingTiers);
	}

	public static class Builder {
		private Builder() {}

		private String modelName;
		private String matchPattern;
		private ModelUsageUnit unit;
		private Double inputPrice;
		private Double outputPrice;
		private Double totalPrice;
		private String tokenizerId;
		private Map<String, Object> tokenizerConfig;
		private Instant startDate;
		private List<PricingTierInput> pricingTiers;

		public Builder modelName(String modelName) {
			this.modelName = modelName;
			return this;
		}

		public Builder matchPattern(String matchPattern) {
			this.matchPattern = matchPattern;
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

		public Builder startDate(Instant startDate) {
			this.startDate = startDate;
			return this;
		}

		public Builder pricingTiers(List<PricingTierInput> pricingTiers) {
			this.pricingTiers = pricingTiers;
			return this;
		}

		public CreateModelRequest build() {
			return new CreateModelRequest(
				modelName, matchPattern, unit, inputPrice, outputPrice,
				totalPrice, tokenizerId, tokenizerConfig, startDate, pricingTiers
			);
		}
	}
}
