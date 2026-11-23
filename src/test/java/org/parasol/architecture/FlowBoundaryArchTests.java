package org.parasol.architecture;

import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameStartingWith;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.sql.Connection;

import jakarta.persistence.EntityManager;

import org.hibernate.query.QueryProducer;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * quarkus-flow is a black box: production and test code reach its persisted runs only through its public API
 * ({@code PersistenceInstanceReader}, {@code WorkflowDefinition}), never through its tables or its JPA layer, so a change to Flow's schema
 * (1.4.0 dropped a column) can't break the build. Over both roots, main and test code alike.
 * <p>
 *   ArchUnit sees calls and class references, not the SQL in a string, so the table-name half of the rule is enforced by proxy: native SQL
 *   and raw JDBC are allowed only in the claim domain, which owns its tables ({@code ClaimImageSeeder}'s advisory lock, {@code ClaimTests}'
 *   and {@code ClaimImageTests}' column checks).
 * </p>
 */
@AnalyzeClasses(packages = { "org.parasol", "ai.scoring" })
class FlowBoundaryArchTests {
	@ArchTest
	static final ArchRule NOTHING_DEPENDS_ON_FLOWS_PERSISTENCE_LAYER = noClasses()
		.that()
		.resideInAnyPackage("org.parasol..", "ai.scoring..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("io.quarkiverse.flow.persistence..")
		.because("Flow's JPA entities and repositories are its schema in Java form; its persisted runs are read through PersistenceInstanceReader");

	@ArchTest
	static final ArchRule NATIVE_SQL_ONLY_IN_THE_CLAIM_DOMAIN = noClasses()
		.that()
		.resideInAnyPackage("org.parasol..", "ai.scoring..")
		.and()
		.resideOutsideOfPackage("org.parasol.claim..")
		.should()
		.callMethodWhere(target(nameStartingWith("createNative"))
			.and(target(owner(assignableTo(EntityManager.class).or(assignableTo(QueryProducer.class))))))
		.orShould()
		.callMethodWhere(target(owner(assignableTo(Connection.class))))
		.because("native SQL outside the claim domain could only be reading a table this application doesn't own, such as Flow's, which "
			+ "PersistenceInstanceReader covers; the claim domain owns its tables");
}
