package org.parasol.notification.guardrail;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import org.parasol.notification.model.ClaimInfo;
import org.parasol.notification.model.Email;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.guardrail.OutputGuardrailResult;

@ApplicationScoped
public class EmailContainsRequiredInformationOutputGuardrail extends GenerateEmailOutputGuardrail {
	static final String REPROMPT_MESSAGE = "Invalid email %s";
	static final String REPROMPT_PROMPT = "Please provide an email %s that has at least one character";
	static final String CLIENT_NAME_NOT_FOUND_MESSAGE = "Client name not found";
	static final String CLIENT_NAME_NOT_FOUND_PROMPT = "The email body did not contain the client name. Please include the client name \"%s\", exactly as is (case-sensitive), in the email body.";
	static final String CLAIM_NUMBER_NOT_FOUND_MESSAGE = "Claim number not found";
	static final String CLAIM_NUMBER_NOT_FOUND_PROMPT = "The email body did not contain the claim number. Please include the claim number \"%s\", exactly as is (case-sensitive), in the email body.";
	static final String CLAIM_STATUS_NOT_FOUND_MESSAGE = "Claim status not found";
	static final String CLAIM_STATUS_NOT_FOUND_PROMPT = "The email body did not contain the claim status. Please include the claim status \"%s\", exactly as is (case-sensitive), in the email body.";

	@Override
	public OutputGuardrailResult validate(OutputGuardrailRequest request) {
		return validate(request.responseFromLLM().aiMessage(), claimInfoOf(request));
	}

	@Override
	public OutputGuardrailResult validate(AiMessage responseFromLLM) {
		return validate(responseFromLLM, Optional.empty());
	}

	private OutputGuardrailResult validate(AiMessage responseFromLLM, Optional<ClaimInfo> claimInfo) {
		return extractEmail(responseFromLLM)
			.map(email -> validateEmail(email, claimInfo))
			.orElseGet(() -> invalidJson(responseFromLLM));
	}

	private OutputGuardrailResult validateEmail(Email email, Optional<ClaimInfo> claimInfo) {
		if ((email.subject() == null) || email.subject().isBlank()) {
			return reprompt(REPROMPT_MESSAGE.formatted("subject"), REPROMPT_PROMPT.formatted("subject"));
		}

		if ((email.body() == null) || email.body().isBlank()) {
			return reprompt(REPROMPT_MESSAGE.formatted("body"), REPROMPT_PROMPT.formatted("body"));
		}

		return claimInfo
			.map(info -> validateClaimInfo(email, info))
			.orElseGet(this::success);
	}

	private OutputGuardrailResult validateClaimInfo(Email email, ClaimInfo claimInfo) {
		if (!claimInfo.clientName().isBlank() && !email.body().contains(claimInfo.clientName())) {
			return reprompt(CLIENT_NAME_NOT_FOUND_MESSAGE, CLIENT_NAME_NOT_FOUND_PROMPT.formatted(claimInfo.clientName()));
		}

		if (!claimInfo.claimNumber().isBlank() && !StringUtils.containsIgnoreCase(email.body(), claimInfo.claimNumber())) {
			return reprompt(CLAIM_NUMBER_NOT_FOUND_MESSAGE, CLAIM_NUMBER_NOT_FOUND_PROMPT.formatted(claimInfo.claimNumber()));
		}

		if (!claimInfo.claimStatus().isBlank() && !StringUtils.containsIgnoreCase(email.body(), claimInfo.claimStatus())) {
			return reprompt(CLAIM_STATUS_NOT_FOUND_MESSAGE, CLAIM_STATUS_NOT_FOUND_PROMPT.formatted(claimInfo.claimStatus()));
		}

		return success();
	}

	private static Optional<ClaimInfo> claimInfoOf(OutputGuardrailRequest request) {
		return Optional.ofNullable(request.requestParams().variables())
			.map(variables -> variables.get("claimInfo"))
			.filter(ClaimInfo.class::isInstance)
			.map(ClaimInfo.class::cast);
	}
}
