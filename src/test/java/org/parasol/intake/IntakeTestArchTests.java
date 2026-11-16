package org.parasol.intake;

import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.core.importer.ImportOption.OnlyIncludeTests;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static org.parasol.intake.IntakeAgentTypes.INTAKE_AGENT_TYPE;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.mockito.InjectSpy;

/**
 * Rules about the intake's tests ({@code OnlyIncludeTests}, so no main-code rule belongs here; those are in
 * {@link IntakeArchTests}).
 */
@AnalyzeClasses(packages = "org.parasol", importOptions = OnlyIncludeTests.class)
class IntakeTestArchTests {
	private static final String MOCK_HAZARD = "quarkus-langchain4j resolves a sub-agent once, when the root agent is built, and unwraps the client "
		+ "proxy, so a mock can stay baked into the root for the life of the application, and the intake tests share one boot with about 54 other classes";

	@ArchTest
	static final ArchRule NO_TEST_MOCKS_OR_SPIES_AN_INTAKE_AGENT = noFields()
		.that()
		.areAnnotatedWith(InjectMock.class)
		.or()
		.areAnnotatedWith(InjectSpy.class)
		.should()
		.haveRawType(INTAKE_AGENT_TYPE)
		.because(MOCK_HAZARD);

	// ArchUnit can't see which type is passed, so this bans the calls outright (none exist). If a legitimate use ever appears, narrow
	// it to a bytecode check of the class-literal argument.
	@ArchTest
	static final ArchRule NO_TEST_INSTALLS_A_MOCK_BY_HAND = noClasses()
		.should()
		.callMethodWhere(target(owner(equivalentTo(QuarkusMock.class))).and(target(name("installMockForType").or(name("installMockForInstance")))))
		.because(MOCK_HAZARD + "; QuarkusMock.installMockForType/installMockForInstance would do the same to an agent without a field to see");
}
