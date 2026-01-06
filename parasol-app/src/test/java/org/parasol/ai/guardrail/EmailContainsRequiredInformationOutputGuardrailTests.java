package org.parasol.ai.guardrail;

import static dev.langchain4j.test.guardrail.GuardrailAssertions.assertThat;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.parasol.ai.ClaimInfo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.GuardrailRequestParams;
import dev.langchain4j.guardrail.GuardrailResult.Result;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.quarkiverse.langchain4j.guardrails.NoopChatExecutor;

@QuarkusTest
class EmailContainsRequiredInformationOutputGuardrailTests {
	private static final String JSON = "{\"subject\":\"This is a subject\",\"body\":\"%s\"}";
	private static final String CLAIM_NUMBER = "CLM195501";
	private static final String CLAIM_STATUS = "denied";
	private static final String CLIENT_NAME = "Marty McFly";
	private static final String EMAIL_TEMPLATE = """
		Dear %s,
		
		We are writing to inform you that your claim (%s) has been reviewed and is currently under consideration. After careful evaluation of the evidence provided, we regret to inform you that your claim has been %s.
		
		Please note that our decision is based on the information provided in your policy declarations page, as well as applicable laws and regulations governing vehicle insurance claims.
		
		If you have any questions or concerns regarding this decision, please do not hesitate to contact us at 800-CAR-SAFE or email claims@parasol.com. A member of our team will be happy to assist you.
		
		Sincerely,
		Parasoft Insurance Claims Department
		
		--------------------------------------------
		Please note this is an unmonitored email box.
		Should you choose to reply, nobody (not even an AI bot) will see your message.
		Call a real human should you have any questions. 1-800-CAR-SAFE.
		""";

	@InjectSpy
	EmailContainsRequiredInformationOutputGuardrail guardrail;

	@Test
	void guardrailSuccess() {
		var body = EMAIL_TEMPLATE.formatted(CLIENT_NAME, CLAIM_NUMBER, CLAIM_STATUS);
		var params = createRequest(body, CLAIM_NUMBER, CLAIM_STATUS, CLIENT_NAME);

		assertThat(this.guardrail.validate(params))
			.isSuccessful();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "    " })
	void invalidSubject(String subject) {
		var json = new StringBuilder("{\"body\": \"Your claim has been denied.\"");

		if (subject != null) {
			json.append(", \"subject\": \"").append(subject).append("\"");
		}

		json.append("}");
		var message = AiMessage.from(json.toString());

		assertThat(this.guardrail.validate(message))
			.hasResult(Result.FATAL)
			.hasSingleFailureWithMessage(EmailContainsRequiredInformationOutputGuardrail.REPROMPT_MESSAGE.formatted("subject"));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "    " })
	void invalidBody(String body) {
		var json = new StringBuilder("{\"subject\": \"Claim Status Update\"");

		if (body != null) {
			json.append(", \"body\": \"").append(body).append("\"");
		}

		json.append("}");
		var message = AiMessage.from(json.toString());

		assertThat(this.guardrail.validate(message))
			.hasResult(Result.FATAL)
			.hasSingleFailureWithMessage(EmailContainsRequiredInformationOutputGuardrail.REPROMPT_MESSAGE.formatted("body"));
	}

	@ParameterizedTest
	@MethodSource("emailDoesntContainRequiredInfoParams")
	void emailDoesntContainRequiredInfo(ClaimInfo missingClaimInfo, String expectedRepromptMessage, String expectedRepromptPrompt) {
		var responseWithMissingInfo = EMAIL_TEMPLATE.formatted(missingClaimInfo.clientName(), missingClaimInfo.claimNumber(), missingClaimInfo.claimStatus());
		var params = createRequest(responseWithMissingInfo, CLAIM_NUMBER, CLAIM_STATUS, CLIENT_NAME);
		var result = this.guardrail.validate(params);

		assertThat(result)
			.hasResult(Result.FATAL)
			.hasSingleFailureWithMessage(expectedRepromptMessage);

		verify(this.guardrail).reprompt(expectedRepromptMessage, expectedRepromptPrompt);
	}

	static Stream<Arguments> emailDoesntContainRequiredInfoParams() {
		return Stream.of(
			Arguments.of(
				new ClaimInfo("", CLAIM_NUMBER, CLAIM_STATUS),
				EmailContainsRequiredInformationOutputGuardrail.CLIENT_NAME_NOT_FOUND_MESSAGE,
				EmailContainsRequiredInformationOutputGuardrail.CLIENT_NAME_NOT_FOUND_PROMPT.formatted(CLIENT_NAME)
			),
			Arguments.of(
				new ClaimInfo(CLIENT_NAME, "", CLAIM_STATUS),
				EmailContainsRequiredInformationOutputGuardrail.CLAIM_NUMBER_NOT_FOUND_MESSAGE,
				EmailContainsRequiredInformationOutputGuardrail.CLAIM_NUMBER_NOT_FOUND_PROMPT.formatted(CLAIM_NUMBER)
			),
			Arguments.of(
				new ClaimInfo(CLIENT_NAME, CLAIM_NUMBER, ""),
				EmailContainsRequiredInformationOutputGuardrail.CLAIM_STATUS_NOT_FOUND_MESSAGE,
				EmailContainsRequiredInformationOutputGuardrail.CLAIM_STATUS_NOT_FOUND_PROMPT.formatted(CLAIM_STATUS)
			)
		);
	}

	private static OutputGuardrailRequest createRequest(String body, String claimNumber, String claimStatus, String clientName) {
		return createRequest(body, new ClaimInfo(clientName, claimNumber, claimStatus));
	}

	private static OutputGuardrailRequest createRequest(String body, ClaimInfo claimInfo) {
		return OutputGuardrailRequest.builder()
		                             .responseFromLLM(
																	 ChatResponse.builder()
																	             .aiMessage(AiMessage.from(JSON.formatted(body).replaceAll("\n", "\\\\n")))
																	             .build()
		                             )
		                             .requestParams(
																	 GuardrailRequestParams.builder()
																	                       .userMessageTemplate("")
																	                       .variables(Map.of("claimInfo", claimInfo))
																	                       .build()
		                             )
		                             .chatExecutor(new NoopChatExecutor())
		                             .build();
	}
}