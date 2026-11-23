package org.parasol.intake;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;

import org.parasol.intake.workflow.ClaimIntakeFlow;

import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.WorkflowDefinition;
import io.serverlessworkflow.impl.WorkflowInstance;
import io.serverlessworkflow.impl.persistence.PersistenceInstanceReader;

/**
 * What quarkus-flow has persisted, read through its public {@link PersistenceInstanceReader}, never its tables
 * ({@code FlowBoundaryArchTests}).
 * <p>
 *   The reader reads the database, not this JVM's memory, so it sees a leaked run that no live instance points at. Since quarkus-flow 1.4.0
 *   it's safe to use for that: it no longer throws for a run whose task ran more than once (quarkiverse/quarkus-flow#1155: every review
 *   that waits again), and what it returns is a new, unstarted instance that isn't registered anywhere (open-workflow-specification/sdk-java#1816),
 *   so reading leaves nothing behind. Its status isn't meaningful: only the id is used here.
 * </p>
 * <p>
 *   No method here holds a transaction: {@code scanAll} begins its own and commits it when its stream is closed (so every stream is
 *   closed), and {@code find} is transactional itself. Called inside a transaction, {@code scanAll} fails with "Transaction already active".
 * </p>
 * <p>
 *   A run's tasks go with it (Flow deletes them with their instance), so no persisted run means no task rows; and the intake stores no
 *   events, since its in-run {@code listen} is always listening already. Neither is counted separately.
 * </p>
 */
// The reader begins a QuarkusTransaction, which needs a request context; a test's teardown has none
@ActivateRequestContext
@ApplicationScoped
class PersistedRuns {
	private static final int SCAN_ATTEMPTS = 5;

	private final PersistenceInstanceReader reader;
	private final WorkflowApplication application;
	private final ClaimIntakeFlow flow;

	PersistedRuns(PersistenceInstanceReader reader, WorkflowApplication application, ClaimIntakeFlow flow) {
		this.reader = reader;
		this.application = application;
		this.flow = flow;
	}

	/**
	 * The ids of every persisted run of the intake's own workflow.
	 */
	List<String> intakeRunIds() {
		return idsOf(this.flow.definition());
	}

	/**
	 * The ids of every persisted run quarkus-flow generated for an agentic system: every workflow but the intake's own.
	 */
	Set<String> generatedRunIds() {
		return otherDefinitions()
			.flatMap(definition -> idsOf(definition).stream())
			.collect(Collectors.toUnmodifiableSet());
	}

	/**
	 * How many runs of every workflow are persisted: the intake's own and the generated ones alike, so unlike
	 * {@link #intakeRunIds()} this can see a leaked generated run.
	 */
	long allRuns() {
		return idsOf(this.flow.definition()).size() + otherDefinitions()
			.mapToLong(definition -> idsOf(definition).size())
			.sum();
	}

	/**
	 * Whether one intake run is still persisted.
	 */
	boolean isPersisted(String runId) {
		return this.reader.find(this.flow.definition(), runId).isPresent();
	}

	// The flow's definition is shared: it must not be closed
	@SuppressWarnings("resource")
	private Stream<WorkflowDefinition> otherDefinitions() {
		var intake = this.flow.definition();

		return this.application.workflowDefinitions()
			.values()
			.stream()
			.filter(definition -> definition != intake);
	}

	private List<String> idsOf(WorkflowDefinition definition) {
		return idsOf(definition, SCAN_ATTEMPTS);
	}

	// Collected before the scan is closed: closing it is what commits the reader's transaction, so a stream returned from inside the
	// try would already be closed for its caller. A run that ends during the scan is deleted between the scan's select and its lazy read
	// of the run's input, which Flow's JPA reader turns into a NullPointerException; it's gone on the next scan, so the scan runs again
	private List<String> idsOf(WorkflowDefinition definition, int attemptsLeft) {
		try (var runs = this.reader.scanAll(definition)) {
			return runs
				.map(WorkflowInstance::id)
				.toList();
		}
		catch (RuntimeException e) {
			if (attemptsLeft <= 1) {
				throw e;
			}

			return idsOf(definition, attemptsLeft - 1);
		}
	}
}
