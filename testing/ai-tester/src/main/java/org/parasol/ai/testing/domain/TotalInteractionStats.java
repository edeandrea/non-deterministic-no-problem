package org.parasol.ai.testing.domain;

import org.parasol.aiinteractions.model.AuditStatsStatsInner;

public record TotalInteractionStats(
	long numInteractions,
	long totalLLmFailures,
	long totalOutputGuardrailExecutions,
	long totalOutputGuardrailFailures
) {
	private TotalInteractionStats(Builder builder) {
		this(builder.numInteractions, builder.totalLLmFailures, builder.totalOutputGuardrailExecutions, builder.totalOutputGuardrailFailures);
	}

	public long totalLLmSuccesses() {
		return this.numInteractions - this.totalLLmFailures;
	}

	public static Builder builder() {
		return new Builder();
	}

	public Builder toBuilder() {
		return new Builder(this);
	}

	public static class Builder {
		long numInteractions;
		long totalLLmFailures;
		long totalOutputGuardrailExecutions;
		long totalOutputGuardrailFailures;

		private Builder() {

		}

		private Builder(TotalInteractionStats source) {
			this.numInteractions = source.numInteractions;
			this.totalLLmFailures = source.totalLLmFailures;
			this.totalOutputGuardrailExecutions = source.totalOutputGuardrailExecutions;
			this.totalOutputGuardrailFailures = source.totalOutputGuardrailFailures;
		}

		public Builder addInteraction(AuditStatsStatsInner interactionStats) {
			this.numInteractions++;
			this.totalLLmFailures += interactionStats.getNumberLlmFailures();
			this.totalOutputGuardrailExecutions += interactionStats.getTotalOutputGuardrailExecutions();
			this.totalOutputGuardrailFailures += interactionStats.getTotalOutputGuardrailFailures();
			return this;
		}

		public TotalInteractionStats build() {
			return new TotalInteractionStats(this);
		}
	}
}
