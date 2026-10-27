package org.parasol.intake.review.rest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import org.parasol.intake.MissingItem;
import org.parasol.intake.review.ReviewOutcome;
import org.parasol.intake.review.ReviewedFields;

/**
 * Enforces how {@link ReviewDecisionRequest}'s parts fit together:
 * <ul>
 *   <li>{@code NEEDS_INFORMATION} asks the customer for at least one item, {@code READY} for none</li>
 *   <li>every item has a value or is asked of the customer, never both and never neither (so a {@code READY} claim has
 *   every value)</li>
 *   <li>an incident time comes only with an incident date</li>
 * </ul>
 * A missing outcome or fields is left to their own {@code @NotNull}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidReviewDecision.Validator.class)
@interface ValidReviewDecision {
	String message() default "the decision's outcome, missing items and fields don't fit together";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<ValidReviewDecision, ReviewDecisionRequest> {
		@Override
		public boolean isValid(ReviewDecisionRequest request, ConstraintValidatorContext context) {
			var problems = problems(request);

			if (!problems.isEmpty()) {
				context.disableDefaultConstraintViolation();
				problems.forEach(problem -> context.buildConstraintViolationWithTemplate(problem).addConstraintViolation());
			}

			return problems.isEmpty();
		}

		static List<String> problems(ReviewDecisionRequest request) {
			return Optional.ofNullable(request)
				.filter(r -> (r.outcome() != null) && (r.fields() != null))
				.map(r -> Stream.of(outcomeProblems(r), itemProblems(r), timeProblems(r.fields().toReviewedFields()))
					.flatMap(List::stream)
					.toList())
				.orElse(List.of());
		}

		private static List<String> outcomeProblems(ReviewDecisionRequest request) {
			var isNeedsInformation = (request.outcome() == ReviewOutcome.NEEDS_INFORMATION);

			return (isNeedsInformation == request.missingItems().isEmpty())
				? List.of(isNeedsInformation ? "missing_items must not be empty for NEEDS_INFORMATION" : "missing_items must be empty for READY")
				: List.of();
		}

		private static List<String> itemProblems(ReviewDecisionRequest request) {
			var fields = request.fields().toReviewedFields();

			return Stream.of(MissingItem.values())
				.flatMap(item -> itemProblem(item, request.missingItems().contains(item), fields.hasValueFor(item)).stream())
				.toList();
		}

		private static Optional<String> itemProblem(MissingItem item, boolean isAsked, boolean hasValue) {
			return (isAsked == hasValue)
				? Optional.of(isAsked ? "%s is asked of the customer, so it can't also have a value".formatted(item.label()) : "%s needs a value unless it's asked of the customer".formatted(item.label()))
				: Optional.empty();
		}

		private static List<String> timeProblems(ReviewedFields fields) {
			return ((fields.incidentTime() != null) && (fields.incidentDate() == null))
				? List.of("incident_time needs an incident_date")
				: List.of();
		}
	}
}
