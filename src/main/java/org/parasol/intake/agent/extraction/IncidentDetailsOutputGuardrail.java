package org.parasol.intake.agent.extraction;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;

import org.parasol.intake.model.IncidentDetails;

import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import dev.langchain4j.guardrails.JsonExtractorOutputGuardrail;

/**
 * Output guardrail for {@link IncidentDetailsAgent}: the answer must be JSON matching {@link IncidentDetails}, with a
 * valid ISO incident date that isn't after the email's sent date, and a valid ISO incident time.
 * <p>
 * JSON parsing and the date checks live in this one guardrail on purpose. A {@link JsonExtractorOutputGuardrail}
 * success is a rewrite, and a rewrite blocks any later guardrail's reprompt with "Retry or reprompt is not allowed
 * after a rewritten output" (#228, see PR #229).
 * <p>
 * The base class does the parsing: it extracts the JSON (fenced or not), hands back the parsed value, and reprompts
 * for malformed JSON (the AI service already appends the JSON schema generated from {@link IncidentDetails} to the
 * user message, and a reprompt resends it). Malformed JSON has nothing to fall back to, so once the reprompts run
 * out, the run fails (spike Q4). This class only adds the date checks on a parsed answer.
 * <p>
 * Every date or time rejection carries an {@link InvalidIncidentDetailsException} with the answer minus the bad
 * fields, so if the model never fixes them, {@link ClaimExtractionWorkflow} treats them as missing instead of failing
 * the run.
 */
@ApplicationScoped
class IncidentDetailsOutputGuardrail extends JsonExtractorOutputGuardrail<IncidentDetails> {
	IncidentDetailsOutputGuardrail() {
		super(IncidentDetails.class);
	}

	@Override
	public OutputGuardrailResult validate(OutputGuardrailRequest request) {
		// sentDate is the agent's own template variable. A missing or malformed one skips only the future-date check
		var sentDate = Optional.ofNullable(request.requestParams().variables().get("sentDate"))
			.filter(String.class::isInstance)
			.map(String.class::cast)
			.flatMap(IsoValues::parseDate);
		var parsed = super.validate(request.responseFromLLM().aiMessage());

		// Only a parsed answer has dates to check; the base class already reprompted for malformed JSON
		return parsed.isSuccess() ? validateDates(parsed, sentDate) : parsed;
	}

	private OutputGuardrailResult validateDates(OutputGuardrailResult parsed, Optional<LocalDate> sentDate) {
		var details = (IncidentDetails) parsed.successfulResult();
		var problems = Stream.of(DateProblem.values())
			.filter(problem -> problem.test(details, sentDate))
			.toList();

		return problems.isEmpty() ?
		       parsed :
		       reprompt(joinMessages(problems), new InvalidIncidentDetailsException(joinMessages(problems), clearInvalidFields(details, problems)), joinReprompts(problems));
	}

	private static String joinMessages(List<DateProblem> problems) {
		return problems.stream()
			.map(DateProblem::message)
			.collect(Collectors.joining("; "));
	}

	private static String joinReprompts(List<DateProblem> problems) {
		return problems.stream()
			.map(DateProblem::reprompt)
			.collect(Collectors.joining("\n"));
	}

	private static IncidentDetails clearInvalidFields(IncidentDetails details, List<DateProblem> problems) {
		var clearDate = problems.stream()
			.anyMatch(DateProblem::isAboutTheDate);
		var clearTime = problems.contains(DateProblem.INVALID_TIME);

		return new IncidentDetails(
			details.description(),
			clearDate ? null : details.incidentDate(),
			clearTime ? null : details.incidentTime(),
			details.location(),
			details.category(),
			details.policyNumber(),
			details.answeredItems()
		);
	}

	private static boolean isPresent(String value) {
		return (value != null) && !value.isBlank();
	}

	/**
	 * Each rule the date and time fields must meet. A blank value meets them all: it's missing, not invalid.
	 */
	enum DateProblem {
		INVALID_DATE(
			"Invalid incidentDate format",
			"incidentDate must be a valid yyyy-MM-dd date string, or null.",
			(details, _) -> isPresent(details.incidentDate()) && IsoValues.parseDate(details.incidentDate()).isEmpty()
		),
		FUTURE_DATE(
			"incidentDate is in the future",
			"incidentDate must not be later than the sentDate, and must be a valid yyyy-MM-dd date string or null.",
			(details, sentDate) -> IsoValues.parseDate(details.incidentDate())
				.flatMap(incident -> sentDate.map(incident::isAfter))
				.orElse(false)
		),
		INVALID_TIME(
			"Invalid incidentTime format",
			"incidentTime must be a valid HH:mm time string, or null.",
			(details, _) -> isPresent(details.incidentTime()) && IsoValues.parseTime(details.incidentTime()).isEmpty()
		);

		private final String message;
		private final String reprompt;
		private final BiPredicate<IncidentDetails, Optional<LocalDate>> check;

		DateProblem(String message, String reprompt, BiPredicate<IncidentDetails, Optional<LocalDate>> check) {
			this.message = message;
			this.reprompt = reprompt;
			this.check = check;
		}

		String message() {
			return this.message;
		}

		String reprompt() {
			return this.reprompt;
		}

		boolean isAboutTheDate() {
			return this != INVALID_TIME;
		}

		boolean test(IncidentDetails details, Optional<LocalDate> sentDate) {
			return this.check.test(details, sentDate);
		}
	}
}
