package org.parasol.intake;

import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackages;
import static com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import jakarta.persistence.Entity;

import org.parasol.intake.workflow.ClaimIntakeFlow;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.declarative.ConditionalAgent;
import dev.langchain4j.agentic.declarative.LoopAgent;
import dev.langchain4j.agentic.declarative.ParallelAgent;
import dev.langchain4j.agentic.declarative.SequenceAgent;

import io.quarkiverse.flow.Flow;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.serverlessworkflow.impl.WorkflowDefinition;

/**
 * The shape of the email intake's packages ({@code org.parasol.intake..}), main code only. The sub-packages form a directed
 * acyclic graph with two leaves, {@code model} and {@code observability}, which everyone else depends on, and the step
 * sub-packages of {@code workflow} sit below the orchestrator in {@code workflow}'s root. Whatever starts, cancels or waits on
 * a run through {@code ClaimIntakeFlow} itself lives next to it in that root.
 */
@AnalyzeClasses(packages = "org.parasol.intake", importOptions = DoNotIncludeTests.class)
class IntakeArchTests {
	private static final String ROOT = "org.parasol.intake";

	@ArchTest
	static final ArchRule INTAKE_PACKAGES_ARE_FREE_OF_CYCLES = slices()
		.matching("org.parasol.intake.(**)")
		.should()
		.beFreeOfCycles()
		.because("a cycle between packages means neither can be understood, tested or extracted without the other");

	@ArchTest
	static final ArchRule MODEL_IS_A_LEAF = noClasses()
		.that()
		.resideInAPackage("org.parasol.intake.model..")
		.should()
		.dependOnClassesThat(resideInAPackage("org.parasol.intake..").and(resideOutsideOfPackages(ROOT, "org.parasol.intake.model..")))
		.because("the records Flow persists and later steps read are what every other intake package builds on");

	@ArchTest
	static final ArchRule OBSERVABILITY_IS_A_LEAF = noClasses()
		.that()
		.resideInAPackage("org.parasol.intake.observability..")
		.should()
		.dependOnClassesThat(resideInAPackage("org.parasol.intake..")
			.and(resideOutsideOfPackages(ROOT, "org.parasol.intake.model..", "org.parasol.intake.config..", "org.parasol.intake.observability..")))
		.because("an event's payload names its subject through a tag enum, not through the producer's own type");

	@ArchTest
	static final ArchRule WORKFLOW_STEP_PACKAGES_DO_NOT_DEPEND_ON_THE_ORCHESTRATOR = noClasses()
		.that()
		.resideInAPackage("org.parasol.intake.workflow.(*)..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("org.parasol.intake.workflow")
		.because("ClaimIntakeFlow calls the steps, so a step that reaches back up to its root makes a cycle");

	@ArchTest
	static final ArchRule EVERY_INTAKE_AI_SERVICE_IS_STATELESS = classes()
		.that()
		.resideInAPackage("org.parasol.intake..")
		.and()
		.areAnnotatedWith(RegisterAiService.class)
		.should(optOutOfMemoryAndRetrieval())
		.because("an intake AI service without both opt-outs silently gets the shared \"default\" chat memory, which leaks context across claims "
			+ "and senders, and the policy-PDF retriever, which is irrelevant to claim email: no build error, no failing test");

	// Not covered, on purpose: a run started through WorkflowApplication directly, or by reflection. Nothing in the intake does either.
	@ArchTest
	static final ArchRule EVERY_INTAKE_RUN_IS_CREATED_BY_NEW_RUN = noClasses()
		.that()
		.resideInAPackage("org.parasol.intake..")
		.and()
		.doNotBelongToAnyOf(ClaimIntakeFlow.class)
		.should()
		.callMethodWhere(target(owner(assignableTo(Flow.class))).and(target(name("instance").or(name("startInstance"))))
			.or(target(owner(assignableTo(WorkflowDefinition.class))).and(target(name("instance")))))
		.because("ClaimIntakeFlow.newRun sets the run's gen_ai.conversation.id metadata, so a run created any other way carries no conversation id "
			+ "on its spans and its claim's Langfuse session is missing a run, with no failing test (activeInstance lookups are fine)");

	// quarkus-flow 1.4.0 puts an agent's name on its spans as gen_ai.agent.name, and falls back to the method name without one
	@ArchTest
	static final ArchRule EVERY_INTAKE_AGENT_IS_NAMED_AFTER_ITS_CLASS = methods()
		.that()
		.areDeclaredInClassesThat()
		.resideInAPackage("org.parasol.intake..")
		.and(agentMethods())
		.should(beNamedAfterTheirClassInKebabCase())
		.because("gen_ai.agent.name is the agent's name attribute, so an unnamed agent shows up in traces under its method name and the "
			+ "composite and leaf agents stop following one rule (a composite's name then also equals its generated workflow's flow.workflow.name)");

	// Flow persists every step's data to PostgreSQL, so run data carries ids and small values. ClaimIntakeState is a JPA entity, not run data.
	@ArchTest
	static final ArchRule RUN_DATA_IS_JSON_SAFE = fields()
		.that()
		.areDeclaredInClassesThat()
		.resideInAPackage("org.parasol.intake.model")
		.and()
		.areDeclaredInClassesThat()
		.areNotAnnotatedWith(Entity.class)
		.and()
		.areNotStatic()
		.should(haveNoUnsafeType())
		.because("Flow persists every step's data: an Optional doesn't round-trip, and bytes or an entity bloat the run's row on every transition");

	private static ArchCondition<JavaField> haveNoUnsafeType() {
		return new ArchCondition<>("not be, or contain as a type argument, an Optional, byte[], InputStream or @Entity") {
			@Override
			public void check(JavaField field, ConditionEvents events) {
				var unsafe = field.getType().getAllInvolvedRawTypes().stream()
					.filter(type -> type.isEquivalentTo(java.util.Optional.class) || type.isEquivalentTo(java.io.InputStream.class) || type.getName().equals("byte[]")
						|| type.isAnnotatedWith(Entity.class))
					.map(JavaClass::getName)
					.toList();
				events.add(new SimpleConditionEvent(field, unsafe.isEmpty(), "%s %s".formatted(field.getFullName(), unsafe.isEmpty() ? "is JSON-safe" : "has the unsafe type(s) " + unsafe)));
			}
		};
	}

	private static final List<Class<?>> AGENT_ANNOTATIONS = List.of(Agent.class, SequenceAgent.class, ParallelAgent.class, ConditionalAgent.class, LoopAgent.class);

	private static DescribedPredicate<JavaMethod> agentMethods() {
		return DescribedPredicate.describe("are agent methods",
			method -> AGENT_ANNOTATIONS.stream().anyMatch(annotation -> method.isAnnotatedWith(annotation.getName())));
	}

	private static final Pattern WORD_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

	private static ArchCondition<JavaMethod> beNamedAfterTheirClassInKebabCase() {
		return new ArchCondition<>("set the agent annotation's name to the kebab-case of the declaring class") {
			@Override
			public void check(JavaMethod method, ConditionEvents events) {
				var expected = WORD_BOUNDARY.matcher(method.getOwner().getSimpleName()).replaceAll("-").toLowerCase(Locale.ROOT);
				var name = AGENT_ANNOTATIONS.stream()
					.filter(annotation -> method.isAnnotatedWith(annotation.getName()))
					.map(annotation -> method.getAnnotationOfType(annotation.getName()).get("name").map(String::valueOf).orElse(""))
					.findFirst()
					.orElse("");
				var isSatisfied = expected.equals(name);
				events.add(new SimpleConditionEvent(method, isSatisfied,
					"%s is named \"%s\", expected \"%s\"".formatted(method.getFullName(), name, expected)));
			}
		};
	}

	private static ArchCondition<JavaClass> optOutOfMemoryAndRetrieval() {
		return new ArchCondition<>("set retrievalAugmentor = NoRetrievalAugmentorSupplier and chatMemoryProviderSupplier = NoChatMemoryProviderSupplier") {
			@Override
			public void check(JavaClass type, ConditionEvents events) {
				var annotation = type.getAnnotationOfType(RegisterAiService.class.getName());
				var retrieval = namesClass(annotation, "retrievalAugmentor", RegisterAiService.NoRetrievalAugmentorSupplier.class);
				var memory = namesClass(annotation, "chatMemoryProviderSupplier", RegisterAiService.NoChatMemoryProviderSupplier.class);
				var isSatisfied = retrieval && memory;
				events.add(new SimpleConditionEvent(type, isSatisfied,
					"%s %s both opt-outs (retrievalAugmentor=%s, chatMemoryProviderSupplier=%s)".formatted(type.getName(), isSatisfied ? "has" : "lacks", retrieval, memory)));
			}
		};
	}

	private static boolean namesClass(JavaAnnotation<JavaClass> annotation, String attribute, Class<?> expected) {
		return annotation.get(attribute)
			.filter(JavaClass.class::isInstance)
			.map(JavaClass.class::cast)
			.map(value -> value.getName().equals(expected.getName()))
			.orElse(false);
	}
}
