package ai.scoring.repository;

import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import ai.scoring.domain.interaction.Interaction;
import ai.scoring.domain.interaction.InteractionQuery;
import ai.scoring.domain.sample.Source;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.logging.Log;
import io.quarkus.panache.common.Parameters;

@ApplicationScoped
public class InteractionRepository implements PanacheRepositoryBase<Interaction, UUID> {
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
		return null;
	}

	private static Parameters parameters(Source source) {
		return Parameters.with("applicationName", source.applicationName())
			.and("interfaceName", source.interfaceName())
			.and("methodName", source.methodName());
	}
}
