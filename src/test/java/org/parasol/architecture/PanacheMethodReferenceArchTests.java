package org.parasol.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import java.util.Set;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodReference;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * Panache only enhances direct calls to its statics, so a method reference to one ({@code Claim::listAll}, {@code ClaimImage::flush})
 * fails at runtime with "did you forget to annotate your entity with @Entity?". Main <em>and</em> test code, so this class imports tests.
 * An entity's own statics ({@code Claim::findByClaimNumber}) and instance references ({@code ClaimImage::delete}) are fine.
 */
@AnalyzeClasses(packages = { "org.parasol", "ai.scoring" })
class PanacheMethodReferenceArchTests {
	private static final Set<String> PANACHE_BASES = Set.of(
		"io.quarkus.hibernate.orm.panache.PanacheEntityBase",
		"io.quarkus.hibernate.orm.panache.PanacheEntity");

	@ArchTest
	static final ArchRule NO_METHOD_REFERENCE_TO_AN_INHERITED_PANACHE_STATIC = classes()
		.should(notReferToAnInheritedPanacheStatic())
		.because("Panache enhances only direct calls to its statics, so such a reference fails at runtime; use a lambda, such as id -> Claim.findById(id)");

	private static ArchCondition<JavaClass> notReferToAnInheritedPanacheStatic() {
		return new ArchCondition<>("not refer, by method reference, to a static declared in PanacheEntityBase or PanacheEntity") {
			@Override
			public void check(JavaClass type, ConditionEvents events) {
				type.getMethodReferencesFromSelf().stream()
					.filter(PanacheMethodReferenceArchTests::isInheritedPanacheStatic)
					.forEach(reference -> events.add(SimpleConditionEvent.violated(reference, reference.getDescription())));
			}
		};
	}

	// The resolved member, not the owner javac wrote: Claim::listAll names Claim but binds to PanacheEntityBase.listAll
	private static boolean isInheritedPanacheStatic(JavaMethodReference reference) {
		return reference.getTarget()
			.resolveMember()
			.stream()
			.anyMatch(PanacheMethodReferenceArchTests::isPanacheStatic);
	}

	private static boolean isPanacheStatic(JavaMethod method) {
		return method.getModifiers().contains(JavaModifier.STATIC) && PANACHE_BASES.contains(method.getOwner().getName());
	}
}
