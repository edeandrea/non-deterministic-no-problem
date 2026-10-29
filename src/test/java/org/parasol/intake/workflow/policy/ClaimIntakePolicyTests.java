package org.parasol.intake.workflow.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.parasol.intake.IntakeFlowTestBase;
import org.parasol.intake.IntakeScenario.Mail;
import org.parasol.testing.StubbedModelsTestProfile;

import io.quarkiverse.wiremock.devservice.ConnectWireMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * The policy-number rules, for new claims and for updates.
 */
@QuarkusTest
@TestProfile(StubbedModelsTestProfile.class)
@ConnectWireMock
class ClaimIntakePolicyTests extends IntakeFlowTestBase {
	// Marty McFly's seeded claim
	private static final String SEEDED_POLICY = "AC-987654321";
	private static final String SEEDED_EMAIL = "marty.mcfly@email.com";

	@Test
	void aPolicyOnAnotherClaimOfTheSameCustomerIsReused() {
		var marker = marker();
		stubNewClaim(marker, withPolicy(SEEDED_POLICY));

		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SEEDED_EMAIL).name("Marty McFly").subject("New accident").body("Another one. " + marker)));

		var claim = this.scenario.awaitClaimOf(SEEDED_EMAIL);

		assertThat(claim.id).isNotEqualTo(1L);
		assertThat(claim.policyNumber).isEqualTo(SEEDED_POLICY);
		assertThat(this.scenario.intake(claim.id).orElseThrow().policyNumberGenerated).isFalse();
	}

	@Test
	void aPolicyOfAnotherCustomerGetsTheVagueReplyAndCreatesNothing() {
		var marker = marker();
		stubNewClaim(marker, withPolicy(SEEDED_POLICY));

		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).name(SENDER_NAME).subject("My accident").body("Policy " + SEEDED_POLICY + ". " + marker)));

		assertThat(this.scenario.awaitReplies(SENDER, 1))
			.singleElement()
			.satisfies(reply -> assertThat(reply.subject()).startsWith("Re: My accident"))
			.satisfies(reply -> assertThat(reply.body()).doesNotContain(SEEDED_POLICY, "Marty", "CLM"));
		assertThat(this.scenario.claimCount()).isEqualTo(6);
		assertThat(this.scenario.intakeStateCount()).as("a rejected email persists no conversation id").isZero();
	}

	@Test
	void anUnknownPolicyIsUsedForTheNewClaim() {
		var marker = marker();
		stubNewClaim(marker, withPolicy("AC-111222333"));

		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).body("Policy AC-111222333. " + marker)));

		var claim = this.scenario.awaitClaimOf(SENDER);

		assertThat(claim.policyNumber).isEqualTo("AC-111222333");
		assertThat(this.scenario.intake(claim.id).orElseThrow().policyNumberGenerated).isFalse();
	}

	@Test
	void aGeneratedPolicyIsUniqueAndMarkedGenerated() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);

		assertThat(created.claim().policyNumber).matches("AC-\\d{9}").isNotEqualTo(SEEDED_POLICY);
		assertThat(this.scenario.intake(created.claim().id).orElseThrow().policyNumberGenerated).isTrue();
	}

	@Test
	void aGeneratedPolicyIsReplacedByTheOneAReplyStates() {
		var created = createClaim(SENDER, details("Car hit a post.", "2026-10-07", null, "SINGLE_VEHICLE", null, List.of()));

		var marker = marker();
		stubFollowUp(marker, details("Car hit a post.", "2026-10-07", "Elm Street", "SINGLE_VEHICLE", "AC-555666777", List.of("LOCATION")));
		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).inReplyTo(created.messageId()).body("Elm Street, policy AC-555666777. " + marker)));

		assertThat(this.scenario.claim(created.claim().id).policyNumber).isEqualTo("AC-555666777");
		assertThat(this.scenario.intake(created.claim().id).orElseThrow().policyNumberGenerated).isFalse();
	}

	@Test
	void anotherCustomersPolicyOnAnUpdateIsRejectedAndALiveReviewIsUntouched() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		var run = this.scenario.awaitWaitingRun(created.claim().id);
		var before = this.scenario.claim(created.claim().id);

		var marker = marker();
		stubFollowUp(marker, withPolicy(SEEDED_POLICY));
		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).inReplyTo(created.messageId()).body("Policy " + SEEDED_POLICY + ". " + marker)));

		assertThat(this.scenario.awaitReplies(SENDER, 2))
			.last()
			.satisfies(reply -> assertThat(reply.body()).doesNotContain(SEEDED_POLICY, "Marty"));
		assertThat(this.scenario.claim(created.claim().id))
			.returns(before.policyNumber, c -> c.policyNumber)
			.returns("Pending Review", c -> c.status)
			.returns(before.body, c -> c.body);
		assertThat(this.scenario.intake(created.claim().id).orElseThrow().reviewRunId).isEqualTo(run.id());
		assertThat(run.status()).isEqualTo(io.serverlessworkflow.impl.WorkflowStatus.WAITING);
	}

	@Test
	void aPolicyTheCustomerGaveIsNeverChangedByAReply() {
		var created = createClaim(SENDER, withPolicy("AC-222333444"));

		var marker = marker();
		stubFollowUp(marker, withPolicy("AC-999888777"));
		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).inReplyTo(created.messageId()).body("Actually AC-999888777. " + marker)));

		assertThat(this.scenario.claim(created.claim().id))
			.returns("AC-222333444", c -> c.policyNumber)
			.satisfies(c -> assertThat(c.body).contains("Actually AC-999888777."));
	}

	private static String withPolicy(String policyNumber) {
		return details("Car hit a post.", "2026-10-07", "Elm Street", "SINGLE_VEHICLE", policyNumber, List.of());
	}
}
