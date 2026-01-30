package ai.scoring.scoring;

import java.util.Map;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import ai.scoring.scoring.AIJudgeScorerTests.AIJudgeProfile;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestTransaction
@TestProfile(AIJudgeProfile.class)
@Disabled("Disabled until we can figure out how to dynamically inject the judge model")
class AIJudgeScorerTests extends InteractionScorerTests {
	@Test
	void aiJudgeRescore() {
		super.rescore();
	}

	public static final class AIJudgeProfile implements QuarkusTestProfile {
		@Override
		public Map<String, String> getConfigOverrides() {
			return Map.of("ai.scoring.scoring-strategy", "ai-judge");
		}
	}
}
