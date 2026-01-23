package org.parasol.ai.scoring.service;

import jakarta.enterprise.context.ApplicationScoped;

import org.parasol.ai.scoring.domain.event.InteractionCompletedEvent;
import org.parasol.ai.scoring.domain.event.InteractionEvent;
import org.parasol.ai.scoring.domain.score.Interaction;
import org.parasol.ai.scoring.domain.score.InteractionScore;
import org.parasol.ai.scoring.evaluation.InteractionScorer;
import org.parasol.ai.scoring.mapping.InteractionMapper;
import org.parasol.ai.scoring.repository.InteractionEventRepository;
import org.parasol.ai.scoring.repository.InteractionRepository;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;

import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;

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

	@WithSpan("storeInteractionEvent")
	public void storeInteractionEvent(@SpanAttribute("arg.event") InteractionEvent event) {
		if (event instanceof InteractionCompletedEvent completedEvent) {
			this.interactionEventRepository.getCorrelatedStartedEvent(completedEvent)
				.ifPresent(startedEvent -> {
					// This event is a completed event and we already have the started event, so we can score it
					// 1) Score the interaction
					// (outside any JDBC transaction
					var score = scoreInteraction(this.interactionMapper.map(startedEvent, completedEvent));

					Log.infof("Interaction %s score: %s", score.getInteraction().getInteractionId(), score.getScore());

					QuarkusTransaction.joiningExisting()
						.run(() -> {
							// 2) Delete its corresponding interaction events
							this.interactionEventRepository.deleteAllForInteractionId(event.getInvocationContext().getInteractionId());

							// 3) Store it as an interaction
							this.interactionRepository.persist(score.getInteraction());
						});
				});
		}
		else {
			// Otherwise just persist the interaction event
			QuarkusTransaction.joiningExisting()
				.run(() -> this.interactionEventRepository.persist(event));
		}
	}

	private InteractionScore scoreInteraction(Interaction interaction) {
		return this.interactionScorer.score(interaction);
	}
}
