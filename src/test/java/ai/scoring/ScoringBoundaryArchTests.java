package ai.scoring;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The boundary of {@code ai.scoring}, seen from the inside: it depends on nothing in the application. This class lives
 * in {@code ai.scoring} rather than {@code org.parasol} so the rule goes with the library if it is extracted.
 */
@AnalyzeClasses(packages = "ai.scoring", importOptions = DoNotIncludeTests.class)
class ScoringBoundaryArchTests {
	@ArchTest
	static final ArchRule SCORING_DEPENDS_ON_NOTHING_IN_THE_APPLICATION = noClasses()
		.that()
		.resideInAPackage("ai.scoring..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("org.parasol..")
		.because("ai.scoring is a reusable AI-quality layer that must be extractable as a library, so it can't know the application that uses it");
}
