package ai.scoring.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import ai.scoring.domain.event.InteractionCompletedEvent;
import ai.scoring.domain.event.InteractionEvent;
import ai.scoring.domain.event.InteractionStartedEvent;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;

@ApplicationScoped
public class InteractionEventRepository implements PanacheRepository<InteractionEvent> {
	public List<InteractionEvent> getAllForInteractionId(UUID interactionId) {
		return list("invocationContext.interactionId", Sort.by("invocationContext.interactionDate"), interactionId);
	}

	@Transactional
	public void deleteAllForInteractionId(UUID interactionId) {
		getAllForInteractionId(interactionId).forEach(this::delete);
	}

	@Transactional
	public Optional<InteractionStartedEvent> getCorrelatedStartedEvent(InteractionCompletedEvent interactionCompletedEvent) {
		return find("FROM InteractionEvent e WHERE e.invocationContext.interactionId = ?1 AND TYPE(e) = ?2", interactionCompletedEvent.getInvocationContext().getInteractionId(), InteractionStartedEvent.class)
			.singleResultOptional();
	}
}
