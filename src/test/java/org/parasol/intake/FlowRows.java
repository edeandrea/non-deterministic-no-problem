package org.parasol.intake;

import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import org.parasol.intake.workflow.ClaimIntakeFlow;

/**
 * What quarkus-flow has persisted, read straight from its tables (it has six; these are the three read here): {@code workflow_instance_entity} (a run),
 * {@code task_info_entity} (a run's tasks) and {@code cloud_event_entity} (the events a waiting run listens for).
 * <p>
 *   These are another extension's tables, so there's no entity of ours to go through and no Panache: a native count is the
 *   only way to read them. Flow's own API is no substitute for a leak check either — {@code flow.definition().activeInstance}
 *   and {@code PersistenceInstanceReader} see the runs this JVM still knows about, and a leaked row is precisely one that no
 *   live instance points at. {@code cloud_event_entity} isn't in Flow's API at all.
 * </p>
 * <p>
 *   Test-only, and the one place the {@link EntityManager} is wired into the intake's test helpers; {@link IntakeScenario}
 *   delegates to it rather than holding native SQL of its own.
 * </p>
 */
@ApplicationScoped
class FlowRows {
	private final EntityManager entityManager;

	FlowRows(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	/**
	 * The ids of every persisted run of the intake's own workflow.
	 */
	@SuppressWarnings("unchecked")
	@Transactional(TxType.REQUIRES_NEW)
	List<String> intakeRunIds() {
		return this.entityManager
			.createNativeQuery("select instance_id from workflow_instance_entity where workflow_name = ?1")
			.setParameter(1, ClaimIntakeFlow.NAME)
			.getResultList();
	}

	/**
	 * The ids of every persisted run quarkus-flow generated for an agentic system: every workflow but the intake's own.
	 */
	@SuppressWarnings("unchecked")
	@Transactional(TxType.REQUIRES_NEW)
	Set<String> generatedRunIds() {
		return Set.copyOf(this.entityManager
			.createNativeQuery("select instance_id from workflow_instance_entity where workflow_name <> ?1")
			.setParameter(1, ClaimIntakeFlow.NAME)
			.getResultList());
	}

	/**
	 * How many instance rows the intake's own workflow has left.
	 */
	@Transactional(TxType.REQUIRES_NEW)
	long intakeInstances() {
		return count("select count(*) from workflow_instance_entity where workflow_name = ?1", ClaimIntakeFlow.NAME);
	}

	/**
	 * How many instance rows there are of <em>every</em> workflow: the intake's own runs and the generated ones alike, so
	 * unlike {@link #intakeInstances()} this can see a leaked generated run.
	 */
	@Transactional(TxType.REQUIRES_NEW)
	long allInstances() {
		return count("select count(*) from workflow_instance_entity");
	}

	/**
	 * How many instance rows one run has left.
	 */
	@Transactional(TxType.REQUIRES_NEW)
	long instances(String runId) {
		return count("select count(*) from workflow_instance_entity where instance_id = ?1", runId);
	}

	/**
	 * How many task rows there are, of every run.
	 */
	@Transactional(TxType.REQUIRES_NEW)
	long allTasks() {
		return count("select count(*) from task_info_entity");
	}

	/**
	 * How many task rows one run has left.
	 */
	@Transactional(TxType.REQUIRES_NEW)
	long tasks(String runId) {
		return count("select count(*) from task_info_entity where workflow_instance_id = ?1", runId);
	}

	/**
	 * How many rows Flow's event table has. Nothing the intake does is known to write one — Flow stores an event only for a
	 * run that isn't listening yet, which the intake's in-run {@code listen} never is — so this is a guard against a change
	 * that starts writing them, not a count that is expected to move.
	 */
	@Transactional(TxType.REQUIRES_NEW)
	long cloudEvents() {
		return count("select count(*) from cloud_event_entity");
	}

	private long count(String sql, Object... parameters) {
		var query = this.entityManager.createNativeQuery(sql);

		for (var i = 0; i < parameters.length; i++) {
			query.setParameter(i + 1, parameters[i]);
		}

		return ((Number) query.getSingleResult()).longValue();
	}
}
