package ai.scoring.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import ai.scoring.domain.interaction.Interaction;
import ai.scoring.domain.interaction.InteractionMode;
import ai.scoring.domain.interaction.InteractionScore;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class InteractionScoreMapperTests {
	@Inject
	InteractionScoreMapper mapper;

	@Test
	void mappingWorks() {
		var interaction = Interaction.builder()
			.interactionId(UUID.randomUUID())
			.interactionDate(Instant.now())
			.applicationName("app")
			.interfaceName("iface")
			.methodName("method")
			.systemMessage("sys")
			.userMessage("user")
			.result("res")
			.build();

		var score = InteractionScore.builder()
			.score(60.0)
			.mode(InteractionMode.NORMAL)
			.scoreDate(Instant.now())
			.interaction(interaction)
			.build();

		interaction.getScores().add(score);

		var mappedScore = this.mapper.map(score);

		assertThat(mappedScore)
			.isNotNull()
			.extracting(
				ai.scoring.model.InteractionScore::getInteractionId,
				ai.scoring.model.InteractionScore::getInteractionDate,
				ai.scoring.model.InteractionScore::getScoreDate,
				ai.scoring.model.InteractionScore::getScore,
				s -> s.getInteractionMode().name()
			)
			.containsExactly(
				interaction.getInteractionId(),
				interaction.getInteractionDate(),
				score.getScoreDate(),
				score.getScore(),
				score.getMode().name()
			);
	}
}