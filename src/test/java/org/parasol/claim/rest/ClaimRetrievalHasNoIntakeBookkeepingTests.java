package org.parasol.claim.rest;

import static io.restassured.RestAssured.get;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.parasol.claim.model.Claim;
import org.parasol.intake.IntakeScenario;
import org.parasol.intake.IntakeTestProfile;
import org.parasol.intake.model.ClaimIntakeState;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import io.restassured.path.json.JsonPath;

/**
 * Fetching a claim is the claim domain's business: none of the intake's bookkeeping (run ids, conversation id, the
 * generated-policy flag, requested items, the lock version) is returned, and none of it is even loaded.
 * <p>
 *   Runs against the real database with a claim that has a fully populated {@link ClaimIntakeState}, and with Hibernate
 *   statistics on, so "not loaded" is measured, not assumed.
 * </p>
 */
@QuarkusTest
@TestProfile(IntakeTestProfile.class)
class ClaimRetrievalHasNoIntakeBookkeepingTests {
	private static final List<String> BOOKKEEPING_KEYS = List.of(
		"review_run_id", "reviewRunId", "deciding_run_id", "decidingRunId", "intake_conversation_id", "intakeConversationId",
		"policy_number_generated", "policyNumberGenerated", "requested_items", "requestedItems", "version", "intake_state", "intakeState");
	private static final List<String> BOOKKEEPING_VALUES = List.of("run-secret-review", "run-secret-deciding", "conversation-secret");


	@Inject
	EntityManagerFactory entityManagerFactory;

	@Inject
	IntakeScenario scenario;

	private long claimId;

	@BeforeEach
	void createClaimWithIntakeState() {
		this.claimId = this.scenario.createClaimWithFullIntakeState();
		statistics().clear();
	}

	@AfterEach
	void deleteClaim() {
		this.scenario.cleanUp();
	}

	@Test
	void oneClaimHasNoBookkeepingInItsJson() {
		var response = get("/api/db/claims/{id}", this.claimId);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(JsonPath.from(response.asString()).getMap(".").keySet()).doesNotContainAnyElementsOf(BOOKKEEPING_KEYS);
		assertThat(response.asString()).doesNotContain(BOOKKEEPING_VALUES);
	}

	@Test
	void theClaimListHasNoBookkeepingInItsJson() {
		var response = get("/api/db/claims");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.asString()).contains("Jane Doe").doesNotContain(BOOKKEEPING_VALUES);
		assertThat(JsonPath.from(response.asString()).<Map<String, Object>>getList("."))
			.allSatisfy(claim -> assertThat(claim.keySet()).doesNotContainAnyElementsOf(BOOKKEEPING_KEYS));
	}

	@Test
	void fetchingClaimsLoadsNoIntakeStateAndNoRequestedItems() {
		get("/api/db/claims/{id}", this.claimId).then().statusCode(200);
		get("/api/db/claims").then().statusCode(200);

		var statistics = statistics();

		assertThat(statistics.getEntityStatistics(Claim.class.getName()).getLoadCount()).as("the claims are loaded").isPositive();
		assertThat(statistics.getEntityStatistics(ClaimIntakeState.class.getName()).getLoadCount()).isZero();
		assertThat(statistics.getEntityStatistics(ClaimIntakeState.class.getName()).getFetchCount()).isZero();
		assertThat(statistics.getCollectionStatistics(ClaimIntakeState.class.getName() + ".requestedItems").getLoadCount()).isZero();
		assertThat(statistics.getCollectionStatistics(ClaimIntakeState.class.getName() + ".requestedItems").getFetchCount()).isZero();
	}

	private org.hibernate.stat.Statistics statistics() {
		return this.entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
	}
}
