package ai.scoring.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import ai.scoring.domain.interaction.Interaction;
import ai.scoring.domain.interaction.RescoreResult;
import ai.scoring.repository.InteractionEventRepository;
import ai.scoring.repository.InteractionRepository;

abstract class InteractionScorerTests {
	static final Instant NOW = Instant.now();
	static final UUID INTERACTION_ID = UUID.randomUUID();
	static final Interaction INTERACTION = Interaction.builder()
	                                                          .interactionDate(NOW)
	                                                          .interactionId(INTERACTION_ID)
	                                                          .applicationName("app")
	                                                          .interfaceName("interface")
	                                                          .methodName("method")
	                                                          .systemMessage("system message")
	                                                          .userMessage("user message")
	                                                          .result("result")
	                                                          .build();

	@Inject
	InteractionRepository interactionRepository;

	@Inject
	InteractionEventRepository interactionEventRepository;

	@Inject
	InteractionScorer interactionScorer;

	@Test
	void score() {
		var scoreTime = Instant.now();

		// interactionScorer.score is backed by wiremock, which will mock the call to cohere
		// See src/test/resources/wiremock/mappings/rerank.json
		assertThat(this.interactionScorer.score(INTERACTION, scoreTime))
			.isNotNull()
			.satisfies(score ->
				assertThat(score.getInteraction())
					.as("Interaction should be the same as the one passed to the scorer")
					.usingRecursiveComparison()
					.ignoringFieldsMatchingRegexes(".*hibernate.*")
					.ignoringFields("scores")
					.isEqualTo(INTERACTION)
			)
			.satisfies(score ->
				assertThat(score.getScoreDate())
					.as("Score date should be the same as the one passed to the scorer")
					.isEqualTo(scoreTime)
			)
			.satisfies(score ->
				assertThat(score.getScore())
					.as("Score should be the same as the one returned by the evaluator")
					.isEqualTo(0.8565)
			);
	}

	protected RescoreResult rescore() {
		// Set up the interaction in the database
		// It needs to be there for the sample loader to find
		this.interactionRepository.persist(INTERACTION);

		var newInteractionId = UUID.randomUUID();
		var newInteraction = INTERACTION.toBuilder()
			.interactionId(newInteractionId)
			.result("new result")
			.build();

		return this.interactionScorer.rescore(newInteraction);
	}
}