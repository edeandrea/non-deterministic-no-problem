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
public class InteractionMapperTests {
	@Inject
	InteractionMapper mapper;

	public static void assertMappingCorrect(ai.scoring.model.Interaction actual, Interaction expected) {
		assertThat(actual)
			.isNotNull()
			.extracting(
				ai.scoring.model.Interaction::getInteractionId,
				ai.scoring.model.Interaction::getInteractionDate,
				ai.scoring.model.Interaction::getApplicationName,
				ai.scoring.model.Interaction::getInterfaceName,
				ai.scoring.model.Interaction::getMethodName,
				ai.scoring.model.Interaction::getSystemMessage,
				ai.scoring.model.Interaction::getUserMessage,
				ai.scoring.model.Interaction::getResult
			)
			.containsExactly(
				expected.getInteractionId(),
				expected.getInteractionDate(),
				expected.getApplicationName(),
				expected.getInterfaceName(),
				expected.getMethodName(),
				expected.getSystemMessage(),
				expected.getUserMessage(),
				expected.getResult()
			);

		assertThat(actual.getScores())
			.singleElement()
			.extracting(
				ai.scoring.model.InteractionScore::getInteractionId,
				ai.scoring.model.InteractionScore::getInteractionDate,
				ai.scoring.model.InteractionScore::getScoreDate,
				ai.scoring.model.InteractionScore::getScore,
				s -> s.getInteractionMode().name()
			)
			.containsExactly(
				expected.getInteractionId(),
				expected.getInteractionDate(),
				expected.getScores().getFirst().getScoreDate(),
				expected.getScores().getFirst().getScore(),
				expected.getScores().getFirst().getMode().name()
			);
	}

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

		assertMappingCorrect(this.mapper.map(interaction), interaction);
	}
}