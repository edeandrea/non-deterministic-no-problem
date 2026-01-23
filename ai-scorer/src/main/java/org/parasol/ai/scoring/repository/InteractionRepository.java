package org.parasol.ai.scoring.repository;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import org.parasol.ai.scoring.domain.score.Interaction;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;

@ApplicationScoped
public class InteractionRepository implements PanacheRepositoryBase<Interaction, UUID> {
}
