package ai.scoring.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import ai.scoring.config.AIScoringConfig;
import ai.scoring.domain.interaction.InteractionScore;
import ai.scoring.scoring.SemanticSimilarityScorerTests.SemanticSimilarityProfile;
import io.quarkiverse.langchain4j.testing.evaluation.EvaluationAssertions;
import io.quarkiverse.langchain4j.testing.evaluation.EvaluationReport;
import io.quarkiverse.langchain4j.testing.evaluation.Scorer.EvaluationResult;

@QuarkusTest
@TestTransaction
@TestProfile(SemanticSimilarityProfile.class)
class SemanticSimilarityScorerTests extends InteractionScorerTests {
	@Inject
	AIScoringConfig aiScoringConfig;

	@Test
	void semanticSimilarityRescore() {
		var rescoreResult = super.rescore();
		var report = rescoreResult.evaluationReport();

		assertThat(report.evaluations()).singleElement();

		var evalResult = report.evaluations().getFirst();

		if (evalResult.passed()) {
			assertPassed(evalResult, report, rescoreResult.interactionScore());
		}
		else {
			assertFailed(evalResult, report, rescoreResult.interactionScore());
		}
	}

	private void assertPassed(EvaluationResult<String> evaluationResult, EvaluationReport<String> report, InteractionScore score) {
		EvaluationAssertions.assertThat(report)
			.hasAllPassed()
			.hasScore(100.0)
			.hasScoreGreaterThan(this.aiScoringConfig.semanticSimilarity().threshold())
			.hasEvaluationCount(1);

		assertThat(score)
			.isNotNull()
			.extracting(InteractionScore::getScore)
			.isEqualTo(100.0);

		assertThat(evaluationResult.score())
			.isGreaterThanOrEqualTo(this.aiScoringConfig.semanticSimilarity().threshold());
	}

	private void assertFailed(EvaluationResult<String> evaluationResult, EvaluationReport<String> report, InteractionScore score) {
		EvaluationAssertions.assertThat(report)
			.hasAllPassed()
			.hasScore(0.0)
			.hasScoreLessThan(this.aiScoringConfig.semanticSimilarity().threshold())
			.hasEvaluationCount(1);

		assertThat(score)
			.isNotNull()
			.extracting(InteractionScore::getScore)
			.isEqualTo(0.0);

		assertThat(evaluationResult.score())
			.isLessThanOrEqualTo(this.aiScoringConfig.semanticSimilarity().threshold());
	}

	public static final class SemanticSimilarityProfile implements QuarkusTestProfile {
		@Override
		public Map<String, String> getConfigOverrides() {
			return Map.of("ai.scoring.scoring-strategy", "semantic-similarity");
		}
	}
}
