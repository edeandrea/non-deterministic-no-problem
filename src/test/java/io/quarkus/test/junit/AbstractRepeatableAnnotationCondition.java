package io.quarkus.test.junit;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.Optional;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

public abstract class AbstractRepeatableAnnotationCondition<A extends Annotation> implements ExecutionCondition {
	private final Class<A> annotationType;

	public AbstractRepeatableAnnotationCondition(Class<A> annotationType) {
		this.annotationType = annotationType;
	}

	@Override
	public final ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
		Optional<AnnotatedElement> optionalElement = context.getElement();

		if (optionalElement.isPresent()) {
			AnnotatedElement annotatedElement = optionalElement.get();
			return AnnotationSupport.findRepeatableAnnotations(annotatedElement, this.annotationType)
			                        .stream()
			                        .map(this::evaluate)
			                        .filter(ConditionEvaluationResult::isDisabled)
			                        .findFirst()
			                        .orElse(getNoDisabledConditionsEncounteredResult());
		}
		return getNoDisabledConditionsEncounteredResult();
	}

	protected abstract ConditionEvaluationResult evaluate(A annotation);
	protected abstract ConditionEvaluationResult getNoDisabledConditionsEncounteredResult();
}
