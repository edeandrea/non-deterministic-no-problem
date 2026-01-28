package ai.scoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.scoring.domain.event.InteractionCompletedEvent;
import ai.scoring.domain.event.InteractionEvent;
import ai.scoring.domain.event.InteractionStartedEvent;
import ai.scoring.domain.interaction.Interaction;
import ai.scoring.domain.interaction.InteractionMode;
import ai.scoring.domain.interaction.InteractionScore;
import ai.scoring.domain.interaction.RescoreResult;
import ai.scoring.mapping.InteractionEventMapper;
import ai.scoring.mapping.InteractionMapper;
import ai.scoring.repository.InteractionEventRepository;
import ai.scoring.repository.InteractionRepository;
import ai.scoring.scoring.InteractionScorer;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;

@QuarkusTest
@TestTransaction
class InteractionServiceTests {
	@Inject
	InteractionService interactionService;

	@Inject
	InteractionRepository interactionRepository;

	@Inject
	InteractionEventRepository interactionEventRepository;

	@Inject
	ObjectMapper objectMapper;

	@Inject
	InteractionEventMapper interactionEventMapper;

	@Inject
	InteractionMapper interactionMapper;

	@InjectSpy
	InteractionScorer interactionScorer;


	@Test
	void handlesStartedEvent() throws IOException {
		getAndAssertStartedEvent();
	}

	@Test
	void handlesNormalCompletedEvent() throws IOException {
		getAndAssertNormalCompletedEvent();
	}

	@Test
	void handlesRescoreCompletedEvent() throws IOException {
		var normalCompletedEvent = getAndAssertNormalCompletedEvent();
		var interaction = this.interactionRepository.findById(normalCompletedEvent.getInvocationContext().getInteractionId());

		assertThat(interaction).isNotNull();

		var newStartedEvent = loadEvent("interaction-started.json", ai.scoring.model.InteractionStartedEvent.class);
		var rescoreCompletedEvent = loadEvent("interaction-completed-rescore.json", ai.scoring.model.InteractionCompletedEvent.class);

		newStartedEvent.getInvocationContext().setInteractionId(UUID.randomUUID());
		rescoreCompletedEvent.getInvocationContext().setInteractionId(newStartedEvent.getInvocationContext().getInteractionId());

		doReturn(
			new RescoreResult(
					InteractionScore.builder()
					                .interaction(interaction)
					                .score(0.5)
													.scoreDate(Instant.now())
					                .build(),
null))
			.when(this.interactionScorer)
			.rescore(argThat(i -> i.getInteractionId().equals(rescoreCompletedEvent.getInvocationContext().getInteractionId())));

		this.interactionService.handleInteractionEvent(newStartedEvent, InteractionMode.RESCORE);
		var score = this.interactionService.handleInteractionEvent(rescoreCompletedEvent, InteractionMode.RESCORE);

		assertThat(score)
			.get()
			.extracting(InteractionScore::getScore)
			.isEqualTo(0.5);
	}

	private InteractionCompletedEvent getAndAssertNormalCompletedEvent() throws IOException {
		cleanRepos();
		var interactionStartedEvent = getAndAssertStartedEvent();
		InteractionCompletedEvent interactionCompletedEvent = loadEvent("interaction-completed-normal.json", ai.scoring.model.InteractionCompletedEvent.class);

		assertThat(this.interactionRepository.count()).isZero();
		var score = this.interactionService.handleInteractionEvent(interactionCompletedEvent, InteractionMode.NORMAL);

		assertThat(score)
			.get()
			.extracting(InteractionScore::getScore)
			.isEqualTo(0.8565);

		var interaction = score.get().getInteraction();

		assertThat(interaction)
			.usingRecursiveComparison()
			.ignoringFieldsMatchingRegexes(".*hibernate.*")
			.ignoringFields("scores")
			.isEqualTo(this.interactionMapper.map(interactionStartedEvent, interactionCompletedEvent));

		assertThat(interaction.getScores())
			.singleElement()
			.usingRecursiveComparison()
			.isEqualTo(score.get());

		assertThat(this.interactionEventRepository.count()).isZero();
		assertThat(this.interactionRepository.count()).isOne();

		return interactionCompletedEvent;
	}

	private InteractionStartedEvent getAndAssertStartedEvent() throws IOException {
		cleanRepos();
		InteractionStartedEvent interactionStartedEvent = loadEvent("interaction-started.json", ai.scoring.model.InteractionStartedEvent.class);

		assertThat(this.interactionService.handleInteractionEvent(interactionStartedEvent, InteractionMode.NORMAL))
			.isEmpty();

		assertThat(this.interactionEventRepository.getAllForInteractionId(interactionStartedEvent.getInvocationContext().getInteractionId()))
			.singleElement()
			.isInstanceOf(InteractionStartedEvent.class)
			.usingRecursiveComparison()
			.ignoringFields("id")
			.isEqualTo(interactionStartedEvent);

		return interactionStartedEvent;
	}

	private void cleanRepos() {
		this.interactionEventRepository.streamAll()
			.map(InteractionEvent::getId)
			.forEach(this.interactionEventRepository::deleteById);

		this.interactionRepository.streamAll()
			.map(Interaction::getInteractionId)
			.forEach(this.interactionRepository::deleteById);

		assertThat(this.interactionEventRepository.count()).isZero();
		assertThat(this.interactionRepository.count()).isZero();
	}

	@SuppressWarnings("unchecked")
	private <T extends InteractionEvent, A extends ai.scoring.model.InteractionEvent> T loadEvent(String fileName, Class<A> clazz) throws IOException {
		try (var is = InteractionServiceTests.class.getClassLoader().getResourceAsStream(fileName)) {
			return (T) this.interactionEventMapper.map(this.objectMapper.readValue(is, clazz));
		}
	}
}