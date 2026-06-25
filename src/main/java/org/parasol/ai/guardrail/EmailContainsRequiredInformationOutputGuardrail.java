package org.parasol.ai.guardrail;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import org.parasol.ai.ClaimInfo;
import org.parasol.ai.Email;

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
		var result = super.validate(request);

		if (result.isSuccess()) {
			var email = (Email) result.successfulResult();
			var claimInfo = Optional.ofNullable(request.requestParams().variables())
			                        .map(vars -> (ClaimInfo) vars.get("claimInfo"))
			                        .orElse(null);

			if (claimInfo != null) {
				if (!claimInfo.clientName().isBlank() && !email.body().contains(claimInfo.clientName())) {
					return reprompt(CLIENT_NAME_NOT_FOUND_MESSAGE, CLIENT_NAME_NOT_FOUND_PROMPT.formatted(claimInfo.clientName()));
				}

				if (!claimInfo.claimNumber().isBlank() && !StringUtils.containsIgnoreCase(email.body(), claimInfo.claimNumber())) {
					return reprompt(CLAIM_NUMBER_NOT_FOUND_MESSAGE, CLAIM_NUMBER_NOT_FOUND_PROMPT.formatted(claimInfo.claimNumber()));
				}

				if (!claimInfo.claimStatus().isBlank() && !StringUtils.containsIgnoreCase(email.body(), claimInfo.claimStatus())) {
					return reprompt(CLAIM_STATUS_NOT_FOUND_MESSAGE, CLAIM_STATUS_NOT_FOUND_PROMPT.formatted(claimInfo.claimStatus()));
				}
			}
		}

		return result;
	}

	@Override
	public OutputGuardrailResult validate(AiMessage responseFromLLM) {
		var result = super.validate(responseFromLLM);

		if (result.isSuccess()) {
			var email = (Email) result.successfulResult();

			if ((email.subject() == null) || email.subject().isBlank()) {
				return reprompt(REPROMPT_MESSAGE.formatted("subject"), REPROMPT_PROMPT.formatted("subject"));
			}

			if ((email.body() == null) || email.body().isBlank()) {
				return reprompt(REPROMPT_MESSAGE.formatted("body"), REPROMPT_PROMPT.formatted("body"));
			}
		}

		return result;
	}
}
