package org.parasol.architecture;

import static com.tngtech.archunit.core.importer.ImportOption.OnlyIncludeTests;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import java.util.stream.Stream;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * Test class names, over the test code of both roots. Surefire's default includes are {@code *Tests}-style names, so a misnamed class
 * silently never runs; and {@code -Dtest='*ArchTests'} selects exactly the ArchUnit classes.
 */
@AnalyzeClasses(packages = { "org.parasol", "ai.scoring" }, importOptions = OnlyIncludeTests.class)
class TestNamingArchTests {
	private static final String JUNIT = "org.junit.jupiter.api.";
	private static final String ARCH_TEST = "com.tngtech.archunit.junit.ArchTest";
	private static final String ANALYZE_CLASSES = "com.tngtech.archunit.junit.AnalyzeClasses";

	@ArchTest
	static final ArchRule TEST_CLASSES_END_IN_TESTS = classes()
		.that()
		.areTopLevelClasses()
		.and()
		.areNotInterfaces()
		.and(new DescribedPredicate<>("declare a test") {
			@Override
			public boolean test(JavaClass type) {
				return declaresATest(type);
			}
		})
		.should()
		.haveSimpleNameEndingWith("Tests")
		.because("surefire only runs classes whose names match its includes, so a class named FooSpec or FooIT silently never runs");

	@ArchTest
	static final ArchRule ARCHUNIT_CLASSES_END_IN_ARCH_TESTS = classes()
		.that(new DescribedPredicate<>("are ArchUnit classes") {
			@Override
			public boolean test(JavaClass type) {
				return isArchUnitClass(type);
			}
		})
		.should()
		.haveSimpleNameEndingWith("ArchTests")
		.because("-Dtest='*ArchTests' runs every rule in seconds, with no application boot, so it must find all of them");

	@ArchTest
	static final ArchRule ONLY_ARCHUNIT_CLASSES_END_IN_ARCH_TESTS = classes()
		.that()
		.haveSimpleNameEndingWith("ArchTests")
		.should(new ArchCondition<>("be an ArchUnit class (carry @AnalyzeClasses or declare an @ArchTest field or method)") {
			@Override
			public void check(JavaClass type, ConditionEvents events) {
				events.add(new SimpleConditionEvent(type, isArchUnitClass(type), "%s %s an ArchUnit class".formatted(type.getName(), isArchUnitClass(type) ? "is" : "is not")));
			}
		})
		.because("-Dtest='*ArchTests' must select exactly the rules, so no other class may carry the suffix");

	private static boolean isArchUnitClass(JavaClass type) {
		return type.isAnnotatedWith(ANALYZE_CLASSES)
			|| type.getFields().stream().anyMatch(field -> field.isAnnotatedWith(ARCH_TEST))
			|| type.getMethods().stream().anyMatch(method -> method.isAnnotatedWith(ARCH_TEST));
	}

	// No @Nested class exists today; a nested class is skipped (the rule takes top-level classes), so a test in one would need this to look through the members
	private static boolean declaresATest(JavaClass type) {
		return type.getMethods().stream()
			.anyMatch(method -> Stream.of("Test", "ParameterizedTest", "RepeatedTest", "TestFactory")
				.anyMatch(annotation -> method.isAnnotatedWith(JUNIT + annotation)))
			|| isArchUnitClass(type);
	}
}
