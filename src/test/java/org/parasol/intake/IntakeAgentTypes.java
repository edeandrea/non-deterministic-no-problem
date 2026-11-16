package org.parasol.intake;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;

import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * What counts as an intake agent type, defined once for the rules in {@link IntakeArchTests} and {@link IntakeTestArchTests}: a
 * class in {@code org.parasol.intake..} that is a {@code @RegisterAiService}, or declares a method carrying one of LangChain4j's
 * agent annotations ({@code @Agent}, {@code @SequenceAgent}, {@code @ParallelAgent}, {@code @ConditionalAgent}, {@code @LoopAgent},
 * {@code @SupervisorAgent}, …: matched by package and an {@code Agent} suffix, so a new kind is covered without a change here).
 * The hooks that sit on agents ({@code @BeforeCall}, {@code @Output}, {@code @ErrorHandler}, {@code @ActivationCondition}) don't
 * make a type an agent.
 */
final class IntakeAgentTypes {
	private static final String AGENTIC_PACKAGE = "dev.langchain4j.agentic";

	static final DescribedPredicate<JavaClass> INTAKE_AGENT_TYPE = new DescribedPredicate<>("an intake agent type") {
		@Override
		public boolean test(JavaClass type) {
			return type.getPackageName().startsWith("org.parasol.intake")
				&& (type.isAnnotatedWith(RegisterAiService.class) || declaresAgentMethod(type));
		}
	};

	private IntakeAgentTypes() {
	}

	private static boolean declaresAgentMethod(JavaClass type) {
		return type.getMethods()
			.stream()
			.flatMap(method -> method.getAnnotations().stream())
			.map(JavaAnnotation::getRawType)
			.anyMatch(annotation -> annotation.getPackageName().startsWith(AGENTIC_PACKAGE) && annotation.getSimpleName().endsWith("Agent"));
	}
}
