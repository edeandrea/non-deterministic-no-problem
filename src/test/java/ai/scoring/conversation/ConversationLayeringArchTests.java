package ai.scoring.conversation;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The layering rule of the conversation core: the core never references an adapter (a sub-package) or a framework, and
 * adapters never reference each other. ArchUnit sees class references in signatures, bodies and annotations, so it also
 * catches fully qualified uses an import check would miss, but not a class named only in a string constant.
 */
@AnalyzeClasses(packages = "ai.scoring.conversation", importOptions = DoNotIncludeTests.class)
class ConversationLayeringArchTests {
	private static final String CORE = "ai.scoring.conversation";
	private static final String ADAPTERS = "ai.scoring.conversation.*..";

	@ArchTest
	static final ArchRule THE_CORE_REFERENCES_NO_ADAPTER = noClasses()
		.that()
		.resideInAPackage(CORE)
		.should()
		.dependOnClassesThat()
		.resideInAPackage(ADAPTERS)
		.because("adapters feed the core, and the core must work without any of them");

	@ArchTest
	static final ArchRule THE_CORE_REFERENCES_NOTHING_ELSE_IN_SCORING = noClasses()
		.that()
		.resideInAPackage(CORE)
		.should()
		.dependOnClassesThat(resideInAPackage("ai.scoring..").and(resideOutsideOfPackage("ai.scoring.conversation..")))
		.because("the conversation core is the bottom layer of ai.scoring: evaluation, drift detection and Langfuse build on it, not the other way round");

	@ArchTest
	static final ArchRule THE_CORE_REFERENCES_NO_FRAMEWORK = noClasses()
		.that()
		.resideInAPackage(CORE)
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("io.quarkus..", "io.quarkiverse..", "dev.langchain4j..", "io.serverlessworkflow..", "io.vertx..")
		.because("the core depends only on the OpenTelemetry API/SDK, CDI and JBoss Logging; anything framework-specific belongs in an adapter");

	@ArchTest
	static final ArchRule THE_CORE_REFERENCES_NOTHING_IN_THE_APPLICATION = noClasses()
		.that()
		.resideInAPackage(CORE)
		.should()
		.dependOnClassesThat()
		.resideInAPackage("org.parasol..")
		.because("the core is generic and ready to be extracted, so it can't know the application that uses it");

	@ArchTest
	static final ArchRule ADAPTERS_NEVER_REFERENCE_EACH_OTHER = slices()
		.matching("ai.scoring.conversation.(*)..")
		.should()
		.notDependOnEachOther()
		.because("each adapter ties the core to one framework, and consumers observe the core's events rather than call an adapter");
}
