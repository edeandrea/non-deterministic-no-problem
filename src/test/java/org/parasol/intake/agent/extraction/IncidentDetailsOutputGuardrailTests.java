package org.parasol.intake.agent.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.parasol.claim.model.ClaimCategory;
import org.parasol.intake.MissingItem;
import org.parasol.intake.model.IncidentDetails;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.GuardrailRequestParams;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import dev.langchain4j.guardrails.JsonExtractorOutputGuardrail;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.quarkiverse.langchain4j.guardrails.NoopChatExecutor;

class IncidentDetailsOutputGuardrailTests {
	private static final String SENT_DATE = "2026-10-08";
	private static final String VALID_JSON = """
		{"description":"car hit a tree","incidentDate":"2026-10-07","incidentTime":"14:30","location":"Elm Street","category":"SINGLE_VEHICLE","policyNumber":null,"answeredItems":["LOCATION"]}""";
	private static final IncidentDetails VALID = new IncidentDetails(
		"car hit a tree",
		"2026-10-07",
		"14:30",
		"Elm Street",
		ClaimCategory.SINGLE_VEHICLE,
		null,
		Set.of(MissingItem.LOCATION)
	);

	private final IncidentDetailsOutputGuardrail guardrail = new IncidentDetailsOutputGuardrail();

	@Test
	void validJsonPassesAndHandsBackTheParsedDetails() {
		var result = this.guardrail.validate(requestFor(VALID_JSON, SENT_DATE));

		assertThat(result)
			.returns(true, OutputGuardrailResult::isSuccess)
			.returns(VALID, OutputGuardrailResult::successfulResult);
	}

	@Test
	void fencedJsonIsExtractedAndPasses() {
		var fenced = """
			Here are the details:
			```json
			%s
			```
			""".formatted(VALID_JSON);

		var result = this.guardrail.validate(requestFor(fenced, SENT_DATE));

		assertThat(result)
			.returns(true, OutputGuardrailResult::isSuccess)
			.returns(VALID, OutputGuardrailResult::successfulResult)
			.returns(VALID_JSON, OutputGuardrailResult::successfulText);
	}

	@Test
	void malformedJsonGetsTheBaseClassRepromptAndHasNoFallback() {
		var result = this.guardrail.validate(requestFor("this is not json", SENT_DATE));

		assertThat(result)
			.returns(true, OutputGuardrailResult::isReprompt)
			.returns(Optional.of(JsonExtractorOutputGuardrail.DEFAULT_REPROMPT_PROMPT), OutputGuardrailResult::getReprompt);
		assertThat(result.<OutputGuardrailResult.Failure>failures())
			.singleElement()
			.extracting(OutputGuardrailResult.Failure::cause)
			.isNull();
	}

	@Test
	void invalidIncidentDateIsRepromptedAndFallsBackToNoDate() {
		var result = this.guardrail.validate(requestFor(VALID_JSON.replace("2026-10-07", "2026-13-40"), SENT_DATE));

		assertThat(result.getReprompt())
			.hasValue("incidentDate must be a valid yyyy-MM-dd date string, or null.");
		assertThat(fallbackOf(result))
			.isEqualTo(withDateAndTime(null, "14:30"));
	}

	@Test
	void futureIncidentDateIsRepromptedAndFallsBackToNoDate() {
		var result = this.guardrail.validate(requestFor(VALID_JSON.replace("2026-10-07", "2026-10-09"), SENT_DATE));

		assertThat(result.getReprompt())
			.hasValue("incidentDate must not be later than the sentDate, and must be a valid yyyy-MM-dd date string or null.");
		assertThat(fallbackOf(result))
			.isEqualTo(withDateAndTime(null, "14:30"));
	}

	@Test
	void incidentDateOnTheSentDateIsNotInTheFuture() {
		var result = this.guardrail.validate(requestFor(VALID_JSON.replace("2026-10-07", SENT_DATE), SENT_DATE));

		assertThat(result.isSuccess())
			.isTrue();
	}

	@Test
	void invalidIncidentTimeIsRepromptedAndFallsBackToNoTimeKeepingTheDate() {
		var result = this.guardrail.validate(requestFor(VALID_JSON.replace("14:30", "99:99"), SENT_DATE));

		assertThat(result.getReprompt())
			.hasValue("incidentTime must be a valid HH:mm time string, or null.");
		assertThat(fallbackOf(result))
			.isEqualTo(withDateAndTime("2026-10-07", null));
	}

	@Test
	void anInvalidDateAndTimeAreBothRepromptedInOneGo() {
		var json = VALID_JSON.replace("2026-10-07", "yesterday")
			.replace("14:30", "half two");

		var result = this.guardrail.validate(requestFor(json, SENT_DATE));

		assertThat(result.getReprompt())
			.hasValue("""
				incidentDate must be a valid yyyy-MM-dd date string, or null.
				incidentTime must be a valid HH:mm time string, or null.""");
		assertThat(fallbackOf(result))
			.isEqualTo(withDateAndTime(null, null));
	}

	@Test
	void nullDateAndTimeAreMissingNotInvalid() {
		var json = VALID_JSON.replace("\"2026-10-07\"", "null")
			.replace("\"14:30\"", "null");

		assertThat(this.guardrail.validate(requestFor(json, SENT_DATE)).isSuccess())
			.isTrue();
	}

	@Test
	void malformedSentDateSkipsOnlyTheFutureCheck() {
		var future = VALID_JSON.replace("2026-10-07", "2099-01-01");

		assertThat(this.guardrail.validate(requestFor(future, "not a date")).isSuccess())
			.isTrue();
		assertThat(this.guardrail.validate(requestFor(VALID_JSON.replace("14:30", "99:99"), "not a date")).isReprompt())
			.isTrue();
	}

	@Test
	void missingSentDateSkipsOnlyTheFutureCheck() {
		var future = VALID_JSON.replace("2026-10-07", "2099-01-01");

		assertThat(this.guardrail.validate(requestFor(future, null)).isSuccess())
			.isTrue();
	}

	private static IncidentDetails fallbackOf(OutputGuardrailResult result) {
		assertThat(result.isReprompt())
			.isTrue();

		return result.<OutputGuardrailResult.Failure>failures().stream()
			.map(OutputGuardrailResult.Failure::cause)
			.filter(InvalidIncidentDetailsException.class::isInstance)
			.map(InvalidIncidentDetailsException.class::cast)
			.map(InvalidIncidentDetailsException::fallbackDetails)
			.findFirst()
			.orElseThrow();
	}

	private static IncidentDetails withDateAndTime(String incidentDate, String incidentTime) {
		return new IncidentDetails(
			VALID.description(),
			incidentDate,
			incidentTime,
			VALID.location(),
			VALID.category(),
			VALID.policyNumber(),
			VALID.answeredItems()
		);
	}

	private static OutputGuardrailRequest requestFor(String responseText, String sentDate) {
		// HashMap rather than Map.of: Map.of rejects the null sentDate one test needs
		var variables = new HashMap<String, Object>();
		variables.put("sentDate", sentDate);

		return OutputGuardrailRequest.builder()
			.responseFromLLM(
				ChatResponse.builder()
					.aiMessage(AiMessage.from(responseText))
					.build())
			.requestParams(
				GuardrailRequestParams.builder()
					.userMessageTemplate("")
					.variables(variables)
					.build())
			.chatExecutor(new NoopChatExecutor())
			.build();
	}
}
