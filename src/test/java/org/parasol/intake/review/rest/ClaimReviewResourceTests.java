package org.parasol.intake.review.rest;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response.Status;

import org.junit.jupiter.api.Test;
import org.parasol.claim.model.ClaimCategory;
import org.parasol.intake.IntakeFlowTestBase;
import org.parasol.intake.IntakeScenario;
import org.parasol.intake.IntakeScenario.Mail;
import org.parasol.intake.IntakeTestProfile;
import org.parasol.intake.MissingItem;
import org.parasol.intake.review.ClaimReviewService;
import org.parasol.intake.review.ReviewOutcome;
import org.parasol.intake.review.rest.ReviewDecisionRequest.Fields;
import org.parasol.intake.workflow.ClaimIntakeFlow;
import org.parasol.problem.ProblemDetail;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;

import io.quarkiverse.wiremock.devservice.ConnectWireMock;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.serverlessworkflow.impl.WorkflowInstance;
import io.serverlessworkflow.impl.WorkflowStatus;

/**
 * The review endpoints, against the Compose GreenMail and a stubbed model: both decisions end to end with the reviewed
 * values, the sentiment reassessment, every check that runs before either changes anything (Problem Details), and the
 * races between a reply, a decision and a reassessment on the same claim.
 */
@QuarkusTest
@TestProfile(IntakeTestProfile.class)
@ConnectWireMock
class ClaimReviewResourceTests extends IntakeFlowTestBase {
	// A seeded claim, never Pending Review: no intake state exists for it, so the review is never open
	private static final long NEVER_REVIEWED_CLAIM_ID = 1L;
	private static final long UNKNOWN_CLAIM_ID = -1L;
	// The claim COMPLETE_DETAILS creates, as a reviewer who changes nothing sends it back
	private static final Fields UNCHANGED = new Fields("A car hit a post on Elm Street.", LocalDate.of(2026, 10, 7), null, "Elm Street", ClaimCategory.SINGLE_VEHICLE);

	@Inject
	ClaimIntakeFlow flow;

	@InjectSpy
	ClaimReviewService reviewService;

	@Test
	void readyDecisionMovesTheClaimInProcessWithOneThankYouEmailAndNoFlowRowsLeft() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		var run = this.scenario.awaitWaitingRun(created.claim().id);
		var review = getReview(created.claim().id);

		assertThat(review.open()).isTrue();
		assertThat(review.status()).isEqualTo("Pending Review");

		postDecision(created.claim().id, ready(review.version()))
			.then()
			.statusCode(Status.ACCEPTED.getStatusCode());

		await()
			.atMost(IntakeScenario.WAIT)
			.until(() -> this.scenario.claim(created.claim().id).status.equals("In Process"));

		assertThat(this.scenario.awaitReplies(SENDER, 2))
			.hasSize(2)
			.last()
			.satisfies(reply -> assertThat(reply.body()).contains(created.claim().claimNumber, "In Process"));

		await()
			.atMost(IntakeScenario.WAIT)
			.until(() -> this.scenario.instanceRows() == 0);

		assertThat(run.status()).isEqualTo(WorkflowStatus.COMPLETED);
	}

	@Test
	void needsInformationDecisionMovesTheClaimToPendingInformationListingExactlyTheTickedItems() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		this.scenario.awaitWaitingRun(created.claim().id);
		var review = getReview(created.claim().id);

		var fields = new Fields("A car hit a post on Elm Street, denting the bumper.", LocalDate.of(2026, 10, 6), null, null, null);
		postDecision(created.claim().id, new ReviewDecisionRequest(ReviewOutcome.NEEDS_INFORMATION, Set.of(MissingItem.LOCATION, MissingItem.CATEGORY), fields, review.version()))
			.then()
			.statusCode(Status.ACCEPTED.getStatusCode());

		await().atMost(IntakeScenario.WAIT).until(() -> this.scenario.claim(created.claim().id).status.equals("Pending Information"));
		// The fields the reviewer filled in are set; the ones asked of the customer keep their values until the customer answers
		assertThat(this.scenario.claim(created.claim().id))
			.extracting(c -> c.summary, c -> c.incidentDate, c -> c.location, c -> c.category)
			.containsExactly("A car hit a post on Elm Street, denting the bumper.", LocalDate.of(2026, 10, 6), "Elm Street", ClaimCategory.SINGLE_VEHICLE);
		assertThat(this.scenario.intake(created.claim().id).orElseThrow().requestedItems)
			.containsExactlyInAnyOrder(MissingItem.LOCATION, MissingItem.CATEGORY);
		assertThat(this.scenario.awaitReplies(SENDER, 2))
			.last()
			.satisfies(reply -> assertThat(reply.body()).contains("- location", "- category").doesNotContain("incident description", "incident date"));
		await().atMost(IntakeScenario.WAIT).until(() -> this.scenario.instanceRows() == 0);
	}

	@Test
	void anUnknownClaimIsNotFoundForBothEndpoints() {
		assertThat(getProblem(given().when().get("/api/db/claims/{id}/review", UNKNOWN_CLAIM_ID), Status.NOT_FOUND))
			.isEqualTo(notFound("Claim -1 was not found", "/api/db/claims/-1/review"));

		var request = ready(0L);
		assertThat(getProblem(postDecision(UNKNOWN_CLAIM_ID, request), Status.NOT_FOUND))
			.isEqualTo(notFound("Claim -1 was not found", "/api/db/claims/-1/review-decision"));
	}

	@Test
	void aClaimNeverReviewedIsConflict() {
		var review = getReview(NEVER_REVIEWED_CLAIM_ID);
		assertThat(review.open()).isFalse();

		var problem = getProblem(postDecision(NEVER_REVIEWED_CLAIM_ID, ready(0L)), Status.CONFLICT);

		assertThat(problem.detail()).contains("no review open");
	}

	@Test
	void aPendingReviewClaimWhoseRunIsGoneIsNotFoundAndTheReviewIsRestored() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		var run = this.scenario.awaitWaitingRun(created.claim().id);
		var review = getReview(created.claim().id);

		cancelRun(run.id());
		await().atMost(IntakeScenario.WAIT).until(() -> run.status() == WorkflowStatus.CANCELLED);

		var problem = getProblem(
			postDecision(created.claim().id, ready(review.version())),
			Status.NOT_FOUND);

		assertThat(problem.detail()).contains("no longer exists");

		// The compensating transaction (a failed step-2 check) leaves the claim's reviewRunId exactly as it was
		var state = this.scenario.intake(created.claim().id).orElseThrow();
		assertThat(state.reviewRunId).isEqualTo(run.id());
		assertThat(state.decidingRunId).isNull();
		assertThat(this.scenario.claim(created.claim().id).status).isEqualTo("Pending Review");
	}

	@Test
	void aStaleVersionIsConflict() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		this.scenario.awaitWaitingRun(created.claim().id);
		var review = getReview(created.claim().id);

		var problem = getProblem(
			postDecision(created.claim().id, ready(review.version() + 1)),
			Status.CONFLICT);

		assertThat(problem.detail()).contains("changed");
		assertThat(this.scenario.intake(created.claim().id).orElseThrow().reviewRunId).isNotNull();
	}

	@Test
	void aDecisionAfterASupersedingReplyIsConflict() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		this.scenario.awaitWaitingRun(created.claim().id);
		var staleReview = getReview(created.claim().id);

		var marker = marker();
		stubFollowUp(marker, COMPLETE_DETAILS.replace("Elm Street", "Oak Avenue"));
		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).inReplyTo(created.messageId()).body("Actually Oak Avenue. " + marker)));
		this.scenario.awaitWaitingRun(created.claim().id);

		var problem = getProblem(
			postDecision(created.claim().id, ready(staleReview.version())),
			Status.CONFLICT);

		assertThat(problem.detail()).contains("changed");
	}

	@Test
	void aSecondDecisionWhileTheFirstIsBeingAppliedIsConflict() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		this.scenario.awaitWaitingRun(created.claim().id);
		var review = getReview(created.claim().id);

		postDecision(created.claim().id, ready(review.version()))
			.then()
			.statusCode(Status.ACCEPTED.getStatusCode());

		// The first decision claimed the review, so whether it's still being applied or already applied, the claim has no
		// review open for a second one
		var problem = getProblem(
			postDecision(created.claim().id, ready(review.version())),
			Status.CONFLICT);

		assertThat(problem.detail()).contains("no review open");

		await().atMost(IntakeScenario.WAIT).until(() -> this.scenario.claim(created.claim().id).status.equals("In Process"));
	}

	@Test
	void eachInvalidBodyIsBadRequestAndLeavesTheReviewOpen() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		this.scenario.awaitWaitingRun(created.claim().id);
		var review = getReview(created.claim().id);
		var version = review.version();

		assertBadRequest(postDecision(created.claim().id, new ReviewDecisionRequest(null, Set.of(), UNCHANGED, version)), "outcome");
		assertBadRequest(postDecision(created.claim().id, new ReviewDecisionRequest(ReviewOutcome.READY, Set.of(), null, version)), "fields");
		assertBadRequest(postDecision(created.claim().id, new ReviewDecisionRequest(ReviewOutcome.READY, Set.of(), UNCHANGED, null)), "version");
		assertBadRequest(postDecision(created.claim().id, new ReviewDecisionRequest(ReviewOutcome.READY, Set.of(MissingItem.LOCATION), UNCHANGED, version)),
			"missing_items must be empty for READY");
		assertBadRequest(postDecision(created.claim().id, new ReviewDecisionRequest(ReviewOutcome.NEEDS_INFORMATION, Set.of(), UNCHANGED, version)),
			"missing_items must not be empty for NEEDS_INFORMATION");
		assertBadRequest(postDecision(created.claim().id, new ReviewDecisionRequest(ReviewOutcome.NEEDS_INFORMATION, Set.of(MissingItem.LOCATION), UNCHANGED, version)),
			"location is asked of the customer, so it can't also have a value");
		assertBadRequest(postDecision(created.claim().id, ready(new Fields(UNCHANGED.description(), UNCHANGED.incidentDate(), null, null, UNCHANGED.category()), version)),
			"location needs a value unless it's asked of the customer");
		assertBadRequest(postDecision(created.claim().id, new ReviewDecisionRequest(ReviewOutcome.NEEDS_INFORMATION, Set.of(MissingItem.INCIDENT_DATE),
				new Fields(UNCHANGED.description(), null, LocalTime.NOON, UNCHANGED.location(), UNCHANGED.category()), version)),
			"incident_time needs an incident_date");
		assertBadRequest(postDecision(created.claim().id, ready(
				new Fields(UNCHANGED.description(), LocalDate.now().plusDays(2), null, UNCHANGED.location(), UNCHANGED.category()),
				version
			)),
			"fields.incident_date must be a date in the past or in the present");
		assertBadRequest(postDecision(created.claim().id, ready(
				new Fields("x".repeat(ReviewDecisionRequest.MAX_DESCRIPTION_LENGTH + 1), UNCHANGED.incidentDate(), null, UNCHANGED.location(), UNCHANGED.category()),
				version
			)),
			"description");
		assertBadRequest(postDecision(created.claim().id, ready(
				new Fields(UNCHANGED.description(), UNCHANGED.incidentDate(), null, "x".repeat(ReviewDecisionRequest.MAX_LOCATION_LENGTH + 1), UNCHANGED.category()),
				version
			)),
			"location");

		// What Jackson can't read never reaches Bean Validation, but is still a Problem Details 400
		assertBadRequest(postDecision(created.claim().id, rawBody(version, "\"category\":\"spaceship\"")), "spaceship");
		assertBadRequest(postDecision(created.claim().id, rawBody(version, "\"incident_date\":\"2026-13-45\"")), "2026-13-45");
		assertBadRequest(postDecision(created.claim().id, "{ not json"), "");

		var state = this.scenario.intake(created.claim().id).orElseThrow();
		assertThat(state.reviewRunId).isNotNull();
		assertThat(state.decidingRunId).isNull();
		assertThat(state.version).isEqualTo(version);
	}

	@Test
	void readyDecisionSetsTheReviewedValuesWithTheStatus() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		this.scenario.awaitWaitingRun(created.claim().id);
		var review = getReview(created.claim().id);
		var fields = """
			"description":"  A car hit a lamp post on Elm Street.  ","incident_date":"2026-10-06","incident_time":"15:30",\
			"location":"Elm Street and 3rd","category":"Multiple vehicle\"""";

		postDecision(created.claim().id, rawBody(review.version(), fields))
			.then()
			.statusCode(Status.ACCEPTED.getStatusCode());

		await()
			.atMost(IntakeScenario.WAIT)
			.until(() -> this.scenario.claim(created.claim().id).status.equals("In Process"));

		assertThat(this.scenario.claim(created.claim().id))
			.extracting(c -> c.summary, c -> c.incidentDate, c -> c.incidentTime, c -> c.location, c -> c.category, c -> c.sentiment)
			.containsExactly("A car hit a lamp post on Elm Street.", LocalDate.of(2026, 10, 6), LocalTime.of(15, 30), "Elm Street and 3rd", ClaimCategory.MULTIPLE_VEHICLE, "Calm.");
	}

	@Test
	void anIncidentDateBeforeThePolicyInceptionIsUnprocessableAndLeavesTheReviewOpen() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		this.scenario.awaitWaitingRun(created.claim().id);
		var review = getReview(created.claim().id);
		var inception = this.scenario.claim(created.claim().id).inceptionDate;
		var beforeInception = new Fields(UNCHANGED.description(), inception.minusDays(1), null, UNCHANGED.location(), UNCHANGED.category());

		var problem = getProblem(postDecision(created.claim().id, ready(beforeInception, review.version())), 422);

		assertThat(problem)
			.extracting(ProblemDetail::title, ProblemDetail::detail)
			.containsExactly("Unprocessable Content", "The reviewed values for claim %d were rejected: the incident date %s is before the policy's inception date %s"
				.formatted(created.claim().id, inception.minusDays(1), inception));

		var state = this.scenario.intake(created.claim().id).orElseThrow();
		assertThat(state.reviewRunId).isNotNull();
		assertThat(state.decidingRunId).isNull();
		assertThat(state.version).isEqualTo(review.version());
		assertThat(this.scenario.claim(created.claim().id))
			.extracting(c -> c.status, c -> c.incidentDate)
			.containsExactly("Pending Review", LocalDate.of(2026, 10, 7));

		// The same review still takes a valid decision
		postDecision(created.claim().id, ready(review.version()))
			.then()
			.statusCode(Status.ACCEPTED.getStatusCode());
		await().atMost(IntakeScenario.WAIT).until(() -> this.scenario.claim(created.claim().id).status.equals("In Process"));
	}

	@Test
	void sentimentIsAssessedAgainAndStoredWithoutTouchingTheReview() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		this.scenario.awaitWaitingRun(created.claim().id);
		var review = getReview(created.claim().id);
		this.stubs.answer(SENTIMENT, "  Frustrated: the customer has written twice.  ");

		var assessment = postSentiment(created.claim().id, review.version())
			.then()
			.statusCode(Status.OK.getStatusCode())
			.contentType(ContentType.JSON)
			.extract()
			.as(SentimentAssessment.class);

		assertThat(assessment).isEqualTo(new SentimentAssessment("Frustrated: the customer has written twice."));
		assertThat(this.scenario.claim(created.claim().id))
			.extracting(c -> c.sentiment, c -> c.summary, c -> c.status)
			.containsExactly("Frustrated: the customer has written twice.", "A car hit a post on Elm Street.", "Pending Review");
		// The model was given the claim's whole correspondence
		assertThat(this.stubs.lastMessage(this.stubs.requestFor(SENTIMENT))).contains("First email.");
		assertThat(getReview(created.claim().id))
			.extracting(ClaimReviewState::open, ClaimReviewState::version)
			.containsExactly(true, review.version());
	}

	@Test
	void sentimentNeedsAnOpenReviewAtTheReviewersVersion() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		this.scenario.awaitWaitingRun(created.claim().id);
		var review = getReview(created.claim().id);
		var assessments = this.stubs.countRequestsFor(SENTIMENT);

		assertThat(getProblem(postSentiment(UNKNOWN_CLAIM_ID, 0), Status.NOT_FOUND).detail()).isEqualTo("Claim -1 was not found");
		assertThat(getProblem(postSentiment(NEVER_REVIEWED_CLAIM_ID, 0), Status.CONFLICT).detail()).contains("no review open");
		assertThat(getProblem(postSentiment(created.claim().id, review.version() + 1), Status.CONFLICT).detail()).contains("changed");
		assertBadRequest(given().contentType(ContentType.JSON).body("{}").when().post("/api/db/claims/{id}/sentiment", created.claim().id), "version");

		// A decision in flight closes the review
		this.scenario.claimReview(created.claim().id);
		assertThat(getProblem(postSentiment(created.claim().id, getReview(created.claim().id).version()), Status.CONFLICT).detail()).contains("no review open");

		assertThat(this.stubs.countRequestsFor(SENTIMENT)).isEqualTo(assessments);
		assertThat(this.scenario.claim(created.claim().id).sentiment).isEqualTo("Calm.");
	}

	@Test
	void aFailedAssessmentIsBadGatewayAndKeepsTheSentiment() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		this.scenario.awaitWaitingRun(created.claim().id);
		var review = getReview(created.claim().id);
		this.stubs.failWhenAll(List.of(SENTIMENT));

		assertThat(getProblem(postSentiment(created.claim().id, review.version()), Status.BAD_GATEWAY).detail())
			.contains("couldn't be assessed", "keeps its previous sentiment");
		assertThat(this.scenario.claim(created.claim().id).sentiment).isEqualTo("Calm.");

		this.stubs.answer(SENTIMENT, "   ");
		assertThat(getProblem(postSentiment(created.claim().id, review.version()), Status.BAD_GATEWAY).detail()).contains("couldn't be assessed");
		assertThat(this.scenario.claim(created.claim().id).sentiment).isEqualTo("Calm.");
	}

	@Test
	void aDecisionDuringTheAssessmentWinsAndTheAssessmentIsDiscarded() throws Exception {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		this.scenario.awaitWaitingRun(created.claim().id);
		var review = getReview(created.claim().id);
		this.stubs.answer(SENTIMENT, "Frustrated.", 2000);
		var assessments = this.stubs.countRequestsFor(SENTIMENT);

		var assessment = CompletableFuture.supplyAsync(() -> postSentiment(created.claim().id, review.version()));
		await()
			.atMost(IntakeScenario.WAIT)
			.until(() -> this.stubs.countRequestsFor(SENTIMENT) > assessments);
		postDecision(created.claim().id, ready(review.version()))
			.then()
			.statusCode(Status.ACCEPTED.getStatusCode());

		assertThat(getProblem(assessment.get(30, TimeUnit.SECONDS), Status.CONFLICT).detail()).isNotBlank();
		await().atMost(IntakeScenario.WAIT).until(() -> this.scenario.claim(created.claim().id).status.equals("In Process"));
		assertThat(this.scenario.claim(created.claim().id).sentiment).isEqualTo("Calm.");
	}

	@Test
	void aReplyDuringTheAssessmentSupersedesTheReviewAndTheAssessmentIsDiscarded() throws Exception {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		this.scenario.awaitWaitingRun(created.claim().id);
		var review = getReview(created.claim().id);
		this.stubs.answer(SENTIMENT, "Frustrated.", 10000);
		var assessments = this.stubs.countRequestsFor(SENTIMENT);

		var assessment = CompletableFuture.supplyAsync(() -> postSentiment(created.claim().id, review.version()));
		await()
			.atMost(IntakeScenario.WAIT)
			.until(() -> this.stubs.countRequestsFor(SENTIMENT) > assessments);

		var marker = marker();
		stubFollowUp(marker, COMPLETE_DETAILS.replace("Elm Street", "Oak Avenue"));
		// The reply's own sentiment assessment must not hit the slow stub
		this.stubs.answerWhenAll(List.of(SENTIMENT, marker), "Reassured.");
		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).inReplyTo(created.messageId()).body("Actually Oak Avenue. " + marker)));
		this.scenario.awaitWaitingRun(created.claim().id);

		assertThat(getProblem(assessment.get(30, TimeUnit.SECONDS), Status.CONFLICT).detail()).isNotBlank();
		assertThat(this.scenario.claim(created.claim().id).sentiment).isNotEqualTo("Frustrated.");
		assertThat(getReview(created.claim().id).version()).isNotEqualTo(review.version());
	}

	@Test
	void aReplyAndADecisionRaceAndExactlyOneWins() throws Exception {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		this.scenario.awaitWaitingRun(created.claim().id);
		var review = getReview(created.claim().id);

		var entered = new CountDownLatch(1);
		doAnswer(invocation -> {
			entered.countDown();
			Thread.sleep(300);

			return invocation.callRealMethod();
		})
			.when(this.reviewService)
		  .submit(eq(created.claim().id), eq(review.version()), any());

		var marker = marker();
		stubFollowUp(marker, COMPLETE_DETAILS.replace("Elm Street", "Oak Avenue"));

		var decisionResponse = CompletableFuture.supplyAsync(() ->
			postDecision(created.claim().id, ready(review.version())));

		assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
		var replyId = this.scenario.submit(Mail.from(SENDER).inReplyTo(created.messageId()).body("Actually Oak Avenue. " + marker));
		var decision = decisionResponse.get(30, TimeUnit.SECONDS);

		if (decision.statusCode() == Status.ACCEPTED.getStatusCode()) {
			// The decision won the lock: the claim moves on, and the losing reply is handled once it does (a status
			// reply, since an In Process claim is never updated)
			await()
				.atMost(IntakeScenario.WAIT)
				.until(() -> this.scenario.claim(created.claim().id).status.equals("In Process"));
			this.scenario.awaitFiled(replyId);
			assertThat(this.scenario.awaitReplies(SENDER, 3))
				.last()
				.satisfies(reply -> assertThat(reply.body()).contains(STATUS_ANSWER));
		}
		else {
			// The reply won the lock: it superseded the review before the decision could claim it, so the decision loses
			// and sends no email
			assertThat(getProblem(decision, Status.CONFLICT).detail()).isNotBlank();
			this.scenario.awaitFiled(replyId);
			assertThat(this.scenario.waitingRun(created.claim().id)).isPresent();
			assertThat(this.scenario.awaitReplies(SENDER, 2)).hasSize(2);
		}
	}

	private static ClaimReviewState getReview(long claimId) {
		return given()
			.when()
			.get("/api/db/claims/{id}/review", claimId)
			.then()
			.statusCode(Status.OK.getStatusCode())
			.contentType(ContentType.JSON)
			.extract()
			.as(ClaimReviewState.class);
	}

	private static ReviewDecisionRequest ready(Long version) {
		return ready(UNCHANGED, version);
	}

	private static ReviewDecisionRequest ready(Fields fields, Long version) {
		return new ReviewDecisionRequest(ReviewOutcome.READY, Set.of(), fields, version);
	}

	// A READY decision whose fields are given as raw JSON, for what a record can't express
	private static String rawBody(long version, String fieldsJson) {
		return """
			{"outcome":"READY","missing_items":[],"version":%d,"fields":{%s}}""".formatted(version, fieldsJson);
	}

	private static Response postDecision(long claimId, Object body) {
		return given()
			.contentType(ContentType.JSON)
			.body(body)
			.when()
			.post("/api/db/claims/{id}/review-decision", claimId);
	}

	private static Response postSentiment(long claimId, long version) {
		return given()
			.contentType(ContentType.JSON)
			.body(new SentimentAssessmentRequest(version))
			.when()
			.post("/api/db/claims/{id}/sentiment", claimId);
	}

	private static ProblemDetail getProblem(Response response, Status status) {
		return getProblem(response, status.getStatusCode());
	}

	private static ProblemDetail getProblem(Response response, int status) {
		return response.then()
			.statusCode(status)
			.contentType(ProblemDetail.MEDIA_TYPE)
			.extract()
			.as(ProblemDetail.class);
	}

	private static void assertBadRequest(Response response, String detailContains) {
		assertThat(getProblem(response, Status.BAD_REQUEST).detail())
			.contains(detailContains)
			.doesNotContain("decide.request")
			.doesNotContain("assessSentiment.request");
	}

	private static ProblemDetail notFound(String detail, String instance) {
		return new ProblemDetail("about:blank", "Not Found", Status.NOT_FOUND.getStatusCode(), detail, instance);
	}

	@SuppressWarnings("resource")
	private void cancelRun(String runId) {
		this.flow.definition().activeInstance(runId).ifPresent(WorkflowInstance::cancel);
	}
}
