package ai.scoring.config;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "ai.scoring")
public interface AIScoringConfig {
	enum ScoringStrategy {
		SEMANTIC_SIMILARITY, AI_JUDGE
	}

	@WithDefault("AI_JUDGE")
	ScoringStrategy scoringStrategy();

	AIJudgeConfig aiJudge();
	SemanticSimilarityConfig semanticSimilarity();

	interface SemanticSimilarityConfig {
		@WithDefault("75")
		Double threshold();

		Optional<String> modelConfigName();
	}

	interface AIJudgeConfig {
		Optional<String> modelConfigName();
	}
}
