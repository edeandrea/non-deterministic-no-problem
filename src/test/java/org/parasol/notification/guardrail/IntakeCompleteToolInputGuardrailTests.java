package org.parasol.notification.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimCategory;
import org.parasol.intake.IntakeTestProfile;

import io.quarkiverse.langchain4j.guardrails.ToolInputGuardrailRequest;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

@QuarkusTest
@TestProfile(IntakeTestProfile.class)
class IntakeCompleteToolInputGuardrailTests {
	@Inject
	IntakeCompleteToolInputGuardrail guardrail;

	@ParameterizedTest
	@ValueSource(strings = { "Pending Review", "Pending Information" })
	@TestTransaction
	void refusesWhileIntakeIsOpen(String status) {
		var claim = claimWithStatus(status);

		var result = this.guardrail.validate(requestFor("{\"claimId\": %d, \"status\": \"Approved\"}".formatted(claim.id)));

		assertThat(result.isSuccess()).isFalse();
		assertThat(result.isFatalFailure()).isFalse();
		assertThat(result.errorMessage())
			.isEqualTo(IntakeCompleteToolInputGuardrail.INTAKE_NOT_COMPLETE.formatted(claim.claimNumber, status));
	}

	@Test
	@TestTransaction
	void readsAClaimIdSentAsAString() {
		var claim = claimWithStatus("Pending Review");

		var result = this.guardrail.validate(requestFor("{\"claimId\": \" %d \", \"status\": \"Approved\"}".formatted(claim.id)));

		assertThat(result.isSuccess()).isFalse();
		assertThat(result.errorMessage())
			.isEqualTo(IntakeCompleteToolInputGuardrail.INTAKE_NOT_COMPLETE.formatted(claim.claimNumber, "Pending Review"));
	}

	@Test
	@TestTransaction
	void allowsOnceIntakeIsComplete() {
		var claim = claimWithStatus("In Process");

		assertThat(this.guardrail.validate(requestFor("{\"claimId\": %d, \"status\": \"Approved\"}".formatted(claim.id))).isSuccess())
			.isTrue();
	}

	@Test
	void leavesAnUnknownClaimToTheTool() {
		assertThat(this.guardrail.validate(requestFor("{\"claimId\": 987654321, \"status\": \"Approved\"}")).isSuccess())
			.isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"status\": \"Approved\"}", "{\"claimId\": \"abc\"}", "not json", "" })
	void refusesWhenTheClaimIdCantBeRead(String arguments) {
		var result = this.guardrail.validate(requestFor(arguments));

		assertThat(result.isSuccess()).isFalse();
		assertThat(result.errorMessage()).isEqualTo(IntakeCompleteToolInputGuardrail.CLAIM_ID_UNREADABLE);
	}

	private static ToolInputGuardrailRequest requestFor(String arguments) {
		return new ToolInputGuardrailRequest(
			ToolExecutionRequest.builder().name("updateClaimStatus").arguments(arguments).build(),
			null,
			null
		);
	}

	private static Claim claimWithStatus(String status) {
		var claim = new Claim();
		claim.category = ClaimCategory.OTHER;
		claim.clientName = "Intake Test Client";
		claim.emailAddress = "intake.test@example.com";
		claim.policyNumber = "AC-0000001";
		claim.inceptionDate = LocalDate.of(2020, 1, 1);
		claim.status = status;
		claim.persistAndFlush();
		return claim;
	}
}
