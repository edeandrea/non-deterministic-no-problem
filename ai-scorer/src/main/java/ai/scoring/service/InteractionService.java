package ai.scoring.service;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import ai.scoring.domain.event.InteractionCompletedEvent;
import ai.scoring.domain.event.InteractionEvent;
import ai.scoring.domain.event.InteractionStartedEvent;
import ai.scoring.domain.interaction.Interaction;
import ai.scoring.domain.interaction.InteractionMode;
import ai.scoring.domain.interaction.InteractionScore;
import ai.scoring.domain.interaction.RescoreResult;
import ai.scoring.mapping.InteractionMapper;
import ai.scoring.repository.InteractionEventRepository;
import ai.scoring.repository.InteractionRepository;
import ai.scoring.scoring.InteractionScorer;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;

import io.quarkus.logging.Log;

@ApplicationScoped
public class InteractionService {
	private final InteractionScorer interactionScorer;
	private final InteractionEventRepository interactionEventRepository;
	private final InteractionRepository interactionRepository;
	private final InteractionMapper interactionMapper;

	public InteractionService(InteractionScorer interactionScorer, InteractionEventRepository interactionEventRepository, InteractionRepository interactionRepository, InteractionMapper interactionMapper) {
		this.interactionScorer = interactionScorer;
		this.interactionEventRepository = interactionEventRepository;
		this.interactionRepository = interactionRepository;
		this.interactionMapper = interactionMapper;
	}

	@WithSpan("handleInteractionEvent")
	@Transactional
	public Optional<InteractionScore> handleInteractionEvent(@SpanAttribute("arg.event") InteractionEvent event, @SpanAttribute("arg.interactionMode") InteractionMode interactionMode) {
		return storeInteractionEvent(event)
			.filter(interaction -> event instanceof InteractionCompletedEvent)
			.flatMap(i -> computeInteractionScore(i, interactionMode));
	}

	private Optional<InteractionScore> computeInteractionScore(Interaction completedInteraction, InteractionMode interactionMode) {
		return switch(interactionMode) {
			case NORMAL -> Optional.ofNullable(scoreInteraction(completedInteraction));
			case RESCORE -> rescoreInteraction(completedInteraction, interactionMode)
				.map(interactionScore -> {
					saveInteraction(interactionScore.getInteraction());
					return interactionScore;
				});
		};
	}

	private Optional<InteractionScore> rescoreInteraction(Interaction completedInteraction, InteractionMode interactionMode) {
		if (interactionMode == InteractionMode.RESCORE) {
			var rescoreResult = rescoreInteraction(completedInteraction);
			Log.infof("Interaction %s rescored as %s", rescoreResult.interactionScore().getInteraction().getInteractionId(), rescoreResult.interactionScore().getScore());

			return Optional.ofNullable(rescoreResult.interactionScore());
		}

		return Optional.empty();
	}

	private Optional<Interaction> storeInteractionEvent(InteractionEvent event) {
		return switch (event) {
			case InteractionStartedEvent startedEvent -> {
				storeInteractionStarted(startedEvent);
				yield Optional.empty();
			}
			case InteractionCompletedEvent completedEvent -> storeInteractionCompleted(completedEvent);
			default -> throw new IllegalStateException("Unexpected interaction event: " + event);
		};
	}

	private Optional<Interaction> storeInteractionCompleted(InteractionCompletedEvent interactionCompletedEvent) {
		return this.interactionEventRepository.getCorrelatedStartedEvent(interactionCompletedEvent)
			.map(startedEvent -> this.interactionMapper.map(startedEvent, interactionCompletedEvent))
			.map(interaction -> {
					//				QuarkusTransaction.joiningExisting().call(() -> {
					// Delete its corresponding interaction events
					this.interactionEventRepository.deleteAllForInteractionId(interactionCompletedEvent.getInvocationContext()
					                                                                                   .getInteractionId());

					// Store it as an interaction
					saveInteraction(interaction);

					return interaction;
				});
//				}));
	}

	private Interaction saveInteraction(Interaction interaction) {
		this.interactionRepository.persist(interaction);
//		QuarkusTransaction.joiningExisting().run(() -> this.interactionRepository.persist(interaction));
		return interaction;
	}

	private RescoreResult rescoreInteraction(Interaction interaction) {
		return this.interactionScorer.rescore(interaction);
	}

	private void storeInteractionStarted(InteractionStartedEvent event) {
		this.interactionEventRepository.persist(event);
//		QuarkusTransaction.joiningExisting()
//			.run(() -> this.interactionEventRepository.persist(event));
	}

	private InteractionScore scoreInteraction(Interaction interaction) {
		return this.interactionScorer.score(interaction);
	}
}
