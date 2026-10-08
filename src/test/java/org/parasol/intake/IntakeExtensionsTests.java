package org.parasol.intake;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import org.hibernate.Session;
import org.junit.jupiter.api.Test;

import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import io.serverlessworkflow.impl.persistence.PersistenceInstanceReader;

// The app boots with the agentic extension and the four quarkus-flow modules, and Flow's JPA persistence is live
@QuarkusTest
@TestProfile(IntakeTestProfile.class)
class IntakeExtensionsTests {
	private static final List<String> FLOW_TABLES = List.of("cloud_event_entity", "task_info_entity", "workflow_instance_entity");

	@Inject
	EntityManager entityManager;

	@Test
	void flowPersistenceIsAvailable() {
		assertThat(Arc.container().select(PersistenceInstanceReader.class).isResolvable())
			.isTrue();
	}

	@Test
	void flowTablesAreCreated() {
		// Hibernate's Session, because its native queries are typed (the EntityManager's return a raw List)
		var tables = this.entityManager.unwrap(Session.class)
			.createNativeQuery("""
				select table_name from information_schema.tables
				where table_schema = current_schema() and table_name in (:names)
				order by table_name""", String.class)
			.setParameter("names", FLOW_TABLES)
			.getResultList();

		assertThat(tables)
			.containsExactlyElementsOf(FLOW_TABLES);
	}
}