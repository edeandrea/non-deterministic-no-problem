package org.parasol.architecture;

import static com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import jakarta.interceptor.InterceptorBinding;

import io.opentelemetry.instrumentation.annotations.WithSpan;

/**
 * Methods an interceptor wraps must stay interceptable. ArC's generated interceptor subclass <em>is</em> the bean instance, so a
 * call from inside the bean (self-invocation) is intercepted only while the method can be overridden.
 *
 * <p>Not covered, on purpose:
 * <ul>
 *   <li>{@code @Scheduled}: it isn't an interceptor binding, and Quarkus already fails the build for a private one
 *   ({@code "@Scheduled method must not be private"}, {@code SchedulerProcessor}); a static one is legal there.</li>
 *   <li>A class-level binding: none exists, and it isn't forbidden. It would leave a private helper outside the interceptor, the
 *   same hazard, so add a rule on the day one appears.</li>
 * </ul>
 */
@AnalyzeClasses(packages = { "org.parasol", "ai.scoring" }, importOptions = DoNotIncludeTests.class)
class InterceptedMethodArchTests {
	private static final String BECAUSE = "ArC's interceptor subclass can only intercept an overridable method, so making one private, final or "
		+ "static silently drops the interceptor, with no compile error and no failing test";

	@ArchTest
	static final ArchRule INTERCEPTOR_BOUND_METHODS_ARE_NOT_PRIVATE = noMethods()
		.that()
		.areMetaAnnotatedWith(InterceptorBinding.class)
		.should()
		.bePrivate()
		.because(BECAUSE);

	@ArchTest
	static final ArchRule INTERCEPTOR_BOUND_METHODS_ARE_NOT_FINAL = noMethods()
		.that()
		.areMetaAnnotatedWith(InterceptorBinding.class)
		.should()
		.haveModifier(JavaModifier.FINAL)
		.because(BECAUSE);

	@ArchTest
	static final ArchRule INTERCEPTOR_BOUND_METHODS_ARE_NOT_STATIC = noMethods()
		.that()
		.areMetaAnnotatedWith(InterceptorBinding.class)
		.should()
		.beStatic()
		.because(BECAUSE);

	@ArchTest
	static final ArchRule WITH_SPAN_METHODS_ARE_NOT_PRIVATE = noMethods()
		.that()
		.areAnnotatedWith(WithSpan.class)
		.should()
		.bePrivate()
		.because("Quarkus turns @WithSpan into an interceptor binding at build time. " + BECAUSE);

	@ArchTest
	static final ArchRule WITH_SPAN_METHODS_ARE_NOT_FINAL = noMethods()
		.that()
		.areAnnotatedWith(WithSpan.class)
		.should()
		.haveModifier(JavaModifier.FINAL)
		.because("Quarkus turns @WithSpan into an interceptor binding at build time. " + BECAUSE);

	@ArchTest
	static final ArchRule WITH_SPAN_METHODS_ARE_NOT_STATIC = noMethods()
		.that()
		.areAnnotatedWith(WithSpan.class)
		.should()
		.beStatic()
		.because("Quarkus turns @WithSpan into an interceptor binding at build time. " + BECAUSE);
}
