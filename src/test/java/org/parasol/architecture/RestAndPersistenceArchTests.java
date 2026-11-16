package org.parasol.architecture;

import static com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import java.util.stream.Stream;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import jakarta.persistence.Entity;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;

/**
 * Where entities live and what the REST layer may expose, over both roots, main code only. The REST layer never serializes an entity:
 * resources call the Active Record methods and map to DTO records.
 */
@AnalyzeClasses(packages = { "org.parasol", "ai.scoring" }, importOptions = DoNotIncludeTests.class)
class RestAndPersistenceArchTests {
	@ArchTest
	static final ArchRule ENTITIES_LIVE_IN_A_MODEL_PACKAGE = classes()
		.that()
		.areAnnotatedWith(Entity.class)
		.should()
		.resideInAPackage("..model..")
		.because("a domain's persistent state is its model; the layers above it hold no entities");

	@ArchTest
	static final ArchRule ENDPOINTS_NEVER_EXPOSE_AN_ENTITY = methods()
		.that()
		.areMetaAnnotatedWith(HttpMethod.class)
		.should(notTakeOrReturnAnEntity())
		.because("the REST layer never serializes entities: the DTOs are the API contract, so a new entity column stays out of it until it's added to one");

	@ArchTest
	static final ArchRule RESOURCES_ARE_NAMED_AND_PLACED_ALIKE = classes()
		.that()
		.areAnnotatedWith(Path.class)
		.should()
		.haveSimpleNameEndingWith("Resource")
		.andShould()
		.resideInAPackage("..rest..")
		.because("a reader finds a domain's HTTP surface in its rest package, under a name that says so");

	private static ArchCondition<JavaMethod> notTakeOrReturnAnEntity() {
		return new ArchCondition<>("not take or return an @Entity, directly or as a type argument") {
			@Override
			public void check(JavaMethod method, ConditionEvents events) {
				var entities = Stream.concat(Stream.of(method.getReturnType()), method.getParameterTypes().stream())
					.flatMap(type -> type.getAllInvolvedRawTypes().stream())
					.filter(type -> type.isAnnotatedWith(Entity.class))
					.map(JavaClass::getName)
					.distinct()
					.toList();

				events.add(new SimpleConditionEvent(method, entities.isEmpty(),
					"%s %s %s".formatted(method.getFullName(), entities.isEmpty() ? "exposes no entity in" : "exposes the entity type(s) " + entities + " in", method.getSourceCodeLocation())));
			}
		};
	}
}
