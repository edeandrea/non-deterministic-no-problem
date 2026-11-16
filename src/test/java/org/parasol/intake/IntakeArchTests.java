package org.parasol.intake;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackages;
import static com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The shape of the email intake's packages ({@code org.parasol.intake..}), main code only. The sub-packages form a directed
 * acyclic graph with two leaves, {@code model} and {@code observability}, which everyone else depends on, and the step
 * sub-packages of {@code workflow} sit below the orchestrator in {@code workflow}'s root. Whatever starts, cancels or waits on
 * a run through {@code ClaimIntakeFlow} itself lives next to it in that root.
 */
@AnalyzeClasses(packages = "org.parasol.intake", importOptions = DoNotIncludeTests.class)
class IntakeArchTests {
	private static final String ROOT = "org.parasol.intake";

	@ArchTest
	static final ArchRule INTAKE_PACKAGES_ARE_FREE_OF_CYCLES = slices()
		.matching("org.parasol.intake.(**)")
		.should()
		.beFreeOfCycles()
		.because("a cycle between packages means neither can be understood, tested or extracted without the other");

	@ArchTest
	static final ArchRule MODEL_IS_A_LEAF = noClasses()
		.that()
		.resideInAPackage("org.parasol.intake.model..")
		.should()
		.dependOnClassesThat(resideInAPackage("org.parasol.intake..").and(resideOutsideOfPackages(ROOT, "org.parasol.intake.model..")))
		.because("the records Flow persists and later steps read are what every other intake package builds on");

	@ArchTest
	static final ArchRule OBSERVABILITY_IS_A_LEAF = noClasses()
		.that()
		.resideInAPackage("org.parasol.intake.observability..")
		.should()
		.dependOnClassesThat(resideInAPackage("org.parasol.intake..")
			.and(resideOutsideOfPackages(ROOT, "org.parasol.intake.model..", "org.parasol.intake.config..", "org.parasol.intake.observability..")))
		.because("an event's payload names its subject through a tag enum, not through the producer's own type");

	@ArchTest
	static final ArchRule WORKFLOW_STEP_PACKAGES_DO_NOT_DEPEND_ON_THE_ORCHESTRATOR = noClasses()
		.that()
		.resideInAPackage("org.parasol.intake.workflow.(*)..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("org.parasol.intake.workflow")
		.because("ClaimIntakeFlow calls the steps, so a step that reaches back up to its root makes a cycle");
}
