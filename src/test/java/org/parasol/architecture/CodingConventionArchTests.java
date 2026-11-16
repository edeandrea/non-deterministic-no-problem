package org.parasol.architecture;

import static com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

import java.util.stream.Stream;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import jakarta.inject.Inject;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.smallrye.config.ConfigMapping;


/**
 * The coding conventions in {@code CLAUDE.md} that a static check can see, over both roots. Every exemption is a named constant
 * saying why, so the next reader can tell whether it still holds.
 */
@AnalyzeClasses(packages = { "org.parasol", "ai.scoring" }, importOptions = DoNotIncludeTests.class)
class CodingConventionArchTests {
	/**
	 * Legacy: still uses {@code @Inject} fields ({@code CLAUDE.md} → Conventions says to prefer constructor injection for new code).
	 */
	private static final String LEGACY_FIELD_INJECTION = "org.parasol.notification.service.NotificationService";

	/**
	 * Keeps a JBoss {@code Logger} on purpose: the conversation core may not reference {@code io.quarkus}
	 * ({@code ConversationLayeringArchTests}), so it can't use {@code io.quarkus.logging.Log}.
	 */
	private static final String CORE_LOGGER_USER = "ai.scoring.conversation.ConversationEvents";

	/**
	 * Adapts Jakarta Mail's {@code java.util.Date} to an {@code Instant}, as its comment says.
	 */
	private static final String LEGACY_DATE_ADAPTER = "org.parasol.intake.mailbox.MimeMessageParser";

	// ---- JAX-RS resources

	@ArchTest
	static final ArchRule RESOURCE_CLASSES_ARE_PACKAGE_PRIVATE = classes()
		.that()
		.areAnnotatedWith(Path.class)
		.should()
		.bePackagePrivate()
		.because("a resource needs no public visibility; only its endpoint methods do");

	@ArchTest
	static final ArchRule ENDPOINT_METHODS_ARE_PUBLIC = methods()
		.that()
		.areMetaAnnotatedWith(HttpMethod.class)
		.should()
		.bePublic()
		.because("Quarkus REST serves a 404 for every endpoint whose method isn't public, with no error at build or boot");

	// ---- Injection and executors

	@ArchTest
	static final ArchRule NO_FIELD_INJECTION = fields()
		.that()
		.areAnnotatedWith(Inject.class)
		.should()
		.beDeclaredInClassesThat()
		.haveFullyQualifiedName(LEGACY_FIELD_INJECTION)
		.because("dependencies come through the constructor, which keeps them final and visible");

	@ArchTest
	static final ArchRule NO_MANAGED_EXECUTOR = noClasses()
		.should()
		.dependOnClassesThat()
		.haveFullyQualifiedName("org.eclipse.microprofile.context.ManagedExecutor")
		.because("background and blocking work goes on the @VirtualThreads ExecutorService");

	// ---- Logging: always the simplified io.quarkus.logging.Log API

	@ArchTest
	static final ArchRule LOGGING_USES_THE_SIMPLIFIED_LOG_API = noClasses()
		.that()
		.doNotHaveFullyQualifiedName(CORE_LOGGER_USER)
		.should()
		.dependOnClassesThat()
		.belongToAnyOf(Logger.class)
		.because("every application class, bean or not, logs through the static io.quarkus.logging.Log methods, "
			+ "for which Quarkus generates the logger field at build time");

	@ArchTest
	static final ArchRule NO_STANDARD_STREAMS = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS
		.because("logging goes through a logger; this also covers Throwable.printStackTrace()");

	@ArchTest
	static final ArchRule NO_JAVA_UTIL_LOGGING = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING
		.because("the project logs through JBoss Logging");

	@ArchTest
	static final ArchRule NO_SLF4J = noClasses()
		.should()
		.dependOnClassesThat()
		.resideInAPackage("org.slf4j..")
		.because("the project logs through JBoss Logging");

	// ---- Dates and Lombok

	@ArchTest
	static final ArchRule NO_LEGACY_DATE_TYPES = noClasses()
		.that()
		.doNotHaveFullyQualifiedName(LEGACY_DATE_ADAPTER)
		.should()
		.dependOnClassesThat()
		.belongToAnyOf(java.util.Date.class, java.util.Calendar.class, java.text.SimpleDateFormat.class, java.sql.Date.class, java.sql.Timestamp.class)
		.because("dates and times use java.time");

	@ArchTest
	static final ArchRule NO_LOMBOK = noClasses()
		.should()
		.dependOnClassesThat()
		.resideInAPackage("lombok..")
		.because("builders and accessors are written by hand");

	// ---- Config, transactions and exceptions

	@ArchTest
	static final ArchRule CONFIG_MAPPINGS_USE_THE_APPLICATIONS_OWN_NAMESPACES = classes()
		.that()
		.areAnnotatedWith(ConfigMapping.class)
		.should(haveAnApplicationConfigPrefix())
		.because("application config outside parasol.* and aiscoring.* is 'unrecognized' to Quarkus and warns on every boot (#290)");

	@ArchTest
	static final ArchRule CONFIG_PROPERTY_NEVER_NAMES_AN_APPLICATION_KEY = classes()
		.should(notNameAnApplicationKeyInConfigProperty())
		.because("application keys belong in a @ConfigMapping; @ConfigProperty is for another extension's keys, such as quarkus.mailer.*");

	@ArchTest
	static final ArchRule QUARKUS_TRANSACTION_ONLY_WHERE_TRANSACTIONAL_DOES_NOT_FIT = noClasses()
		.that()
		// Runs inside the AI service's invocation context (the tool call), so it joins the transaction there rather than starting one
		.doNotHaveFullyQualifiedName("org.parasol.notification.service.NotificationService")
		// The tool input guardrail's own read, in the same invocation context and for the same reason
		.and()
		.doNotHaveFullyQualifiedName("org.parasol.notification.guardrail.IntakeCompleteToolInputGuardrail")
		// A private static helper of a startup observer, which no interceptor can bind to
		.and()
		.doNotHaveFullyQualifiedName("org.parasol.intake.dev.DevClaimEmailSeeder")
		.should()
		.dependOnClassesThat()
		.haveFullyQualifiedName("io.quarkus.narayana.jta.QuarkusTransaction")
		.because("@Transactional is preferred; the three exemptions are named above with their reasons");

	@ArchTest
	static final ArchRule EXCEPTIONS_ARE_UNCHECKED = classes()
		.that()
		.areAssignableTo(Throwable.class)
		.should()
		.beAssignableTo(RuntimeException.class)
		.orShould()
		.beAssignableTo(Error.class)
		.because("domain exceptions are unchecked by convention");

	private static ArchCondition<JavaClass> haveAnApplicationConfigPrefix() {
		return new ArchCondition<>("have a prefix of parasol (in org.parasol) or aiscoring (in ai.scoring), never quarkus") {
			@Override
			public void check(JavaClass type, ConditionEvents events) {
				var prefix = type.getAnnotationOfType(ConfigMapping.class.getName())
					.get("prefix")
					.map(String::valueOf)
					.orElse("");
				var root = type.getPackageName().startsWith("ai.scoring") ? "aiscoring" : "parasol";
				var isValid = prefix.equals(root) || prefix.startsWith(root + ".");
				events.add(new SimpleConditionEvent(type, isValid, "%s has @ConfigMapping prefix '%s', expected '%s' or '%s.…'".formatted(type.getName(), prefix, root, root)));
			}
		};
	}

	private static ArchCondition<JavaClass> notNameAnApplicationKeyInConfigProperty() {
		return new ArchCondition<>("not name a parasol.* or aiscoring.* key in @ConfigProperty, on a field, method or constructor parameter") {
			@Override
			public void check(JavaClass type, ConditionEvents events) {
				Stream.of(
						type.getFields().stream().flatMap(field -> field.getAnnotations().stream()),
						type.getMethods().stream().flatMap(method -> method.getAnnotations().stream()),
						type.getMethods().stream().flatMap(method -> method.getParameters().stream()).flatMap(parameter -> parameter.getAnnotations().stream()),
						type.getConstructors().stream().flatMap(constructor -> constructor.getParameters().stream()).flatMap(parameter -> parameter.getAnnotations().stream()))
					.flatMap(annotations -> annotations)
					.filter(annotation -> annotation.getRawType().isEquivalentTo(ConfigProperty.class))
					.map(JavaAnnotation::getProperties)
					.map(properties -> String.valueOf(properties.get("name")))
					.filter(name -> name.startsWith("parasol.") || name.startsWith("aiscoring."))
					.forEach(name -> events.add(SimpleConditionEvent.violated(type, "%s names the application key '%s' in @ConfigProperty".formatted(type.getName(), name))));
			}
		};
	}
}
