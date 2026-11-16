package org.parasol.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import org.parasol.chat.ai.ClaimService;

import ai.scoring.drift.DriftDetectionOutputGuardrail;

/**
 * The top-level boundaries of the application, as seen from outside {@code ai.scoring} and between the {@code org.parasol}
 * domains. They cover main code only: tests reach across freely ({@code org.parasol.ui}, {@code org.parasol.testing}, and the
 * {@code ai.scoring} tests that drive the chat bot).
 */
@AnalyzeClasses(packages = { "org.parasol", "ai.scoring" }, importOptions = DoNotIncludeTests.class)
class DomainBoundaryArchTests {
	private static final String CHAT = "org.parasol.chat..";
	private static final String NOTIFICATION = "org.parasol.notification..";
	private static final String CLAIM = "org.parasol.claim..";
	private static final String INTAKE = "org.parasol.intake..";
	private static final String PROBLEM = "org.parasol.problem..";

	/**
	 * The published API of {@code ai.scoring}: the only classes the application may use, and where from. Today that is the
	 * drift guardrail, which {@link ClaimService} names in {@code @OutputGuardrails}. That edge can't go: quarkus-langchain4j
	 * attaches guardrails only through that build-time annotation, AI services are synthetic beans so a CDI interceptor
	 * can't bind to them, and the guardrail must be able to fail the response, which rules out a {@code ChatModelListener}.
	 * An extracted {@code ai.scoring} would be depended on exactly this way. Add to it on purpose, never by accident.
	 */
	private static final DescribedPredicate<JavaClass> OFF_LIMITS_SCORING_CLASSES = resideInAPackage("ai.scoring..")
		.and(DescribedPredicate.not(equivalentTo(DriftDetectionOutputGuardrail.class)))
		.as("classes in ai.scoring.. other than its published API (%s)".formatted(DriftDetectionOutputGuardrail.class.getSimpleName()));

	private static final String[] DOMAINS = { CHAT, NOTIFICATION, CLAIM, INTAKE, PROBLEM };

	@ArchTest
	static final ArchRule THE_APPLICATION_USES_ONLY_SCORINGS_PUBLISHED_API = noClasses()
		.that()
		.resideInAPackage("org.parasol..")
		.should()
		.dependOnClassesThat(OFF_LIMITS_SCORING_CLASSES)
		.because("ai.scoring must be extractable as a library, so the application may only use its published API");

	@ArchTest
	static final ArchRule ONLY_CHAT_USES_SCORINGS_PUBLISHED_API = noClasses()
		.that()
		.resideInAPackage("org.parasol..")
		.and()
		.resideOutsideOfPackage(CHAT)
		.should()
		.dependOnClassesThat()
		.resideInAPackage("ai.scoring..")
		.because("the chat bot is the only part of the application that opts in to drift detection; the intake could be a separate application");

	@ArchTest
	static final ArchRule THE_APPLICATION_NEVER_HANDLES_THE_CONVERSATION_ID = noClasses()
		.that()
		.resideInAPackage("org.parasol..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("io.opentelemetry.api.baggage..")
		.because("ai.scoring owns the conversation id (OTel baggage), so the application never handles it itself");

	@ArchTest
	static final ArchRule THE_DOMAINS_DEPEND_ON_EACH_OTHER_ONLY_IN_THE_DOCUMENTED_DIRECTIONS = layeredArchitecture()
		.consideringOnlyDependenciesInLayers()
		.layer("Chat").definedBy(CHAT)
		.layer("Notification").definedBy(NOTIFICATION)
		.layer("Claim").definedBy(CLAIM)
		.layer("Intake").definedBy(INTAKE)
		.layer("Problem").definedBy(PROBLEM)
		.whereLayer("Chat").mayNotBeAccessedByAnyLayer()
		.whereLayer("Notification").mayOnlyBeAccessedByLayers("Chat")
		.whereLayer("Claim").mayOnlyBeAccessedByLayers("Notification", "Intake")
		.whereLayer("Intake").mayNotBeAccessedByAnyLayer()
		.whereLayer("Problem").mayOnlyBeAccessedByLayers("Claim", "Intake")
		.because("chat → notification → claim, and intake → claim, so the intake could be a separate application");

	@ArchTest
	static final ArchRule ONLY_THE_CLAIM_DOMAIN_REACHES_ITS_REST_AND_SEED_LAYERS = noClasses()
		.that()
		.resideOutsideOfPackage(CLAIM)
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("org.parasol.claim.rest..", "org.parasol.claim.seed..")
		.because("other domains use the claim model only, never its REST DTOs or seeding");

	@ArchTest
	static final ArchRule PROBLEM_DEPENDS_ON_NO_OTHER_PART_OF_THE_APPLICATION = noClasses()
		.that()
		.resideInAPackage(PROBLEM)
		.should()
		.dependOnClassesThat(resideInAPackage("org.parasol..").and(resideOutsideOfPackage(PROBLEM)))
		.because("RFC 9457 Problem Details is a neutral standard that every domain shares, so it can't know any of them");

	@ArchTest
	static final ArchRule THE_APPLICATIONS_DOMAINS_HAVE_NO_CYCLES = slices()
		.matching("org.parasol.(*)..")
		.should()
		.beFreeOfCycles()
		.because("a cycle between domains means they can't be separated");

	@ArchTest
	static final ArchRule SCORINGS_PACKAGES_HAVE_NO_CYCLES = slices()
		.matching("ai.scoring.(*)..")
		.should()
		.beFreeOfCycles()
		.because("ai.scoring must be extractable, and a cycle between its packages can't be split");

	@ArchTest
	static final ArchRule EVERY_CLASS_BELONGS_TO_A_KNOWN_DOMAIN = classes()
		.that()
		.resideInAPackage("org.parasol..")
		.should()
		.resideInAnyPackage(DOMAINS)
		.because("a new domain must be added to the layers above on purpose, so its direction is decided rather than accidental");
}
