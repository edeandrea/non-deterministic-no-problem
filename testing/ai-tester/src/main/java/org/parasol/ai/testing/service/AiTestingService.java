package org.parasol.ai.testing.service;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.parasol.ai.testing.domain.TotalInteractionStats;
import org.parasol.ai.testing.domain.jpa.Interaction;
import org.parasol.ai.testing.mapping.InteractionMapper;
import org.parasol.ai.testing.repository.InteractionRepository;
import org.parasol.aiinteractions.api.AiInteractionsApi;
import org.parasol.aiinteractions.model.InteractionsInteractionsInner;

import io.smallrye.common.annotation.RunOnVirtualThread;

@ApplicationScoped
@RunOnVirtualThread
public class AiTestingService {
	private static final Instant NOW = Instant.now();
	private final AiInteractionsApi aiInteractionsApi;
	private final InteractionMapper interactionMapper;
	private final InteractionRepository interactionRepository;

	public AiTestingService(@RestClient AiInteractionsApi aiInteractionsApi, InteractionMapper interactionMapper, InteractionRepository interactionRepository) {
		this.aiInteractionsApi = aiInteractionsApi;
		this.interactionMapper = interactionMapper;
		this.interactionRepository = interactionRepository;
	}

	public TotalInteractionStats getTotalInteractionStats(Instant start, Optional<Instant> end, URI endpointUri) {
		return this.aiInteractionsApi.getAIInteractionStats(endpointUri.toString(), end.orElse(NOW), start)
		                             .getStats()
		                             .stream()
		                             .reduce(TotalInteractionStats.builder(), TotalInteractionStats.Builder::addInteraction,
			                             (builder1, builder2) -> builder1)
		                             .build();
	}

	private Stream<InteractionsInteractionsInner> getSuccessfulInteractionFromSource(Instant start, Optional<Instant> end, URI endpointUri) {
		return this.aiInteractionsApi.getAIInteractions(endpointUri.toString(), end.orElse(NOW), start)
		                             .getInteractions()
		                             .stream()
		                             .filter(InteractionsInteractionsInner::getIsSuccess);
	}

	public List<Interaction> getSuccessfulInteractions(Instant start, Optional<Instant> end, URI endpointUri) {
		return getSuccessfulInteractionFromSource(start, end, endpointUri)
			.map(interaction -> interactionMapper.map(interaction, endpointUri))
			.toList();
	}

	@Transactional
	public List<Interaction> getAllStoredInteractions() {
		return this.interactionRepository.listAll();
	}

	@Transactional
	public void storeInteraction(Interaction interaction) {
		this.interactionRepository.persist(interaction);
	}

	@Transactional
	public void storeInteractions(List<Interaction> interactions) {
		Optional.ofNullable(interactions)
		        .orElseGet(List::of)
		        .forEach(this::storeInteraction);
	}
}
