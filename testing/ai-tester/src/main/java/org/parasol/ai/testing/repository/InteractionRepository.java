package org.parasol.ai.testing.repository;

import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import org.parasol.ai.testing.domain.jpa.Interaction;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;

@ApplicationScoped
public class InteractionRepository implements PanacheRepositoryBase<Interaction, UUID> {
	public List<Interaction> findScoredInteractionsSortedByScoreDate() {
		return list("SELECT DISTINCT i FROM Interaction i WHERE SIZE(i.scores) > 0");
	}
}
