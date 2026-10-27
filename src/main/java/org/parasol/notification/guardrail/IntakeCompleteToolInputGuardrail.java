package org.parasol.notification.guardrail;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import org.parasol.claim.model.Claim;
import org.parasol.claim.model.IntakeClaimStatus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.langchain4j.guardrails.ToolInputGuardrail;
import io.quarkiverse.langchain4j.guardrails.ToolInputGuardrailRequest;
import io.quarkiverse.langchain4j.guardrails.ToolInputGuardrailResult;
import io.quarkus.narayana.jta.QuarkusTransaction;

/**
 * Refuses an action tool call on a claim whose intake isn't complete. The refusal goes back to the model, which can explain it. A call whose claim id can't be read is refused too.
 * The tool itself repeats the check in the transaction that writes, so a status change between the two can't slip through.
 */
@ApplicationScoped
public class IntakeCompleteToolInputGuardrail implements ToolInputGuardrail {
	static final String INTAKE_NOT_COMPLETE = "Claim %s has status \"%s\", which means intake isn't complete. No action can be taken on it yet, so nothing was changed and no email was sent";
	static final String CLAIM_ID_UNREADABLE = "The claimId argument is missing or isn't a number, so nothing was changed and no email was sent";

	private final ObjectMapper objectMapper;

	IntakeCompleteToolInputGuardrail(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public ToolInputGuardrailResult validate(ToolInputGuardrailRequest request) {
		return claimId(request)
			.map(this::validateClaim)
			.orElseGet(() -> ToolInputGuardrailResult.failure(CLAIM_ID_UNREADABLE));
	}

	private ToolInputGuardrailResult validateClaim(long claimId) {
		var intakeOpenClaim = findIntakeOpen(claimId);

		return intakeOpenClaim.isEmpty()
			? ToolInputGuardrailResult.success()
			: refusalFor(intakeOpenClaim.orElseThrow());
	}

	// Without a claim id the call can't be checked, so it doesn't go ahead
	private Optional<Long> claimId(ToolInputGuardrailRequest request) {
		return Optional.ofNullable(request.executionRequest().arguments())
			.flatMap(this::claimIdIn);
	}

	private Optional<Long> claimIdIn(String arguments) {
		try {
			var id = this.objectMapper.readTree(arguments).path("claimId");

			return Optional.of(id)
				.filter(node -> node.isIntegralNumber() || node.isTextual())
				.flatMap(IntakeCompleteToolInputGuardrail::toLong);
		}
		catch (JsonProcessingException e) {
			return Optional.empty();
		}
	}

	// A model may send the id as a string; the tool's own argument parsing accepts that, so the guardrail must too
	private static Optional<Long> toLong(JsonNode id) {
		try {
			return Optional.of(Long.parseLong(id.asText().strip()));
		}
		catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

	private static ToolInputGuardrailResult refusalFor(Claim claim) {
		return ToolInputGuardrailResult.failure(INTAKE_NOT_COMPLETE.formatted(claim.claimNumber, claim.status));
	}

	private Optional<Claim> findIntakeOpen(long claimId) {
		return QuarkusTransaction.joiningExisting().call(() ->
			Claim.<Claim>findByIdOptional(claimId)
				.filter(claim -> IntakeClaimStatus.isIntakeOpen(claim.status))
		);
	}
}
