package ai.scoring.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.transaction.Transactional;

import ai.scoring.domain.interaction.Interaction;
import ai.scoring.domain.interaction.InteractionQuery;
import ai.scoring.domain.sample.Source;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.logging.Log;
import io.quarkus.panache.common.Parameters;

@ApplicationScoped
public class InteractionRepository implements PanacheRepositoryBase<Interaction, UUID> {
	private final CriteriaBuilder criteriaBuilder;

	public InteractionRepository(CriteriaBuilder criteriaBuilder) {
		this.criteriaBuilder = criteriaBuilder;
	}

	@Transactional
	public boolean containsSource(Source source) {
		Log.debugf("Checking if source %s exists", source);
		return count("#Interaction.countBySource", parameters(source)) > 0;
	}

	@Transactional
	public List<Interaction> findAllBySource(Source source) {
		Log.debugf("Retrieving interactions for source %s", source);
		return list("#Interaction.findAllBySource", parameters(source));
	}

	@Transactional
	public List<Interaction> findInteractions(InteractionQuery query) {
		var q = this.criteriaBuilder.createQuery(Interaction.class);
		var root = q.from(Interaction.class);
		var predicates = new ArrayList<Predicate>();

		query.getApplicationName()
			.map(applicationName -> this.criteriaBuilder.equal(root.get("applicationName"), applicationName))
			.ifPresent(predicates::add);

		query.getInterfaceName()
			.map(interfaceName -> this.criteriaBuilder.equal(root.get("interfaceName"), interfaceName))
			.ifPresent(predicates::add);

		query.getMethodName()
			.map(methodName -> this.criteriaBuilder.equal(root.get("methodName"), methodName))
			.ifPresent(predicates::add);

		query.getStart()
			.map(start -> this.criteriaBuilder.greaterThanOrEqualTo(root.get("interactionDate"), start))
			.ifPresent(predicates::add);

		query.getEnd()
			.map(end -> this.criteriaBuilder.lessThanOrEqualTo(root.get("interactionDate"), end))
			.ifPresent(predicates::add);

		q.where(predicates.toArray(Predicate[]::new));

		return getEntityManager().createQuery(q).getResultList();
	}

	private static Parameters parameters(Source source) {
		return Parameters.with("applicationName", source.applicationName())
			.and("interfaceName", source.interfaceName())
			.and("methodName", source.methodName());
	}
}
