package org.parasol.architecture;

import static com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import jakarta.inject.Inject;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;

import org.jboss.logging.Logger;


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
}
