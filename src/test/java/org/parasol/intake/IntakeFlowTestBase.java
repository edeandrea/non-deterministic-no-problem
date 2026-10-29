package org.parasol.intake;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.parasol.intake.IntakeScenario.Mail;
import org.parasol.intake.model.EmailType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;

/**
 * Shared setup of the intake workflow tests. Each subclass carries the annotations ({@code @QuarkusTest},
 * {@code @TestProfile(StubbedModelsTestProfile.class)}, {@code @ConnectWireMock}) so every class shares one app boot.
 * <p>
 *   Stubs match on a prompt phrase (which agent) and a marker in the email (which test), so each email is stubbed on its own.
 * </p>
 */
public abstract class IntakeFlowTestBase {
	protected static final String SENDER = "jane.doe@example.com";
	protected static final String SENDER_NAME = "Jane Doe";
	protected static final String CLASSIFY = "Classify the following email";
	protected static final String SUMMARY = "Summarize the following claim correspondence";
	protected static final String SENTIMENT = "Assess the claimant's sentiment";
	protected static final String DETAILS = "Extract the incident details from the following correspondence";
	protected static final String FOLLOW_UP = "Answer the following email about the customer's claim";
	protected static final String RESOLVER = "Decide which of the customer's pending claims";
	protected static final String STATUS_ANSWER = "Hello, your claim is being worked on. Sincerely, the claims department";
	protected static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3 };

	protected static final String COMPLETE_DETAILS = details("Car hit a post.", "2026-10-07", "Elm Street", "SINGLE_VEHICLE", null, List.of());

	@Inject
	protected IntakeScenario scenario;

	@Inject
	protected ObjectMapper objectMapper;

	protected WireMock wiremock;

	protected ChatStubs stubs;

	@BeforeEach
	protected void setUpIntake() {
		this.stubs = new ChatStubs(this.wiremock, this.objectMapper);
		this.stubs.reset();
		this.scenario.reset();
		this.stubs.answer(SUMMARY, "A car hit a post on Elm Street.");
		this.stubs.answer(SENTIMENT, "Calm.");
		this.stubs.callTool(FOLLOW_UP, "findClaimStatus", "{}");
		this.stubs.answer("has the status:", STATUS_ANSWER);
	}

	@AfterEach
	protected void cleanUpIntake() {
		this.scenario.cleanUp();
	}

	protected static String details(String description, String date, String location, String category, String policyNumber, List<String> answered) {
		return """
			{"description":%s,"incidentDate":%s,"incidentTime":null,"location":%s,"category":%s,"policyNumber":%s,"answeredItems":%s}"""
			.formatted(quote(description), quote(date), quote(location), quote(category), quote(policyNumber),
				answered.stream().map(IntakeFlowTestBase::quote).toList());
	}

	protected static String quote(String value) {
		return (value == null) ? "null" : "\"%s\"".formatted(value);
	}

	protected void classify(String marker, EmailType type) {
		this.stubs.answerWhenAll(List.of(CLASSIFY, marker), type.name());
	}

	protected void extract(String marker, String detailsJson) {
		this.stubs.answerWhenAll(List.of(DETAILS, marker), detailsJson);
	}

	/**
	 * Creates a claim by a customer's first email and waits until it's filed. Returns the claim and the email's Message-ID.
	 */
	protected Created createClaim(String address, String detailsJson) {
		var marker = marker();
		stubNewClaim(marker, detailsJson);

		var id = this.scenario.submit(Mail.from(address).name(SENDER_NAME).subject("Car accident").body("First email. " + marker));
		this.scenario.awaitFiled(id);

		return new Created(this.scenario.awaitClaimOf(address), id);
	}

	protected record Created(org.parasol.claim.model.Claim claim, String messageId) {
	}

	protected static String marker() {
		return "[[%s]]".formatted(java.util.UUID.randomUUID());
	}

	protected void stubNewClaim(String marker, String detailsJson) {
		classify(marker, EmailType.NEW_CLAIM);
		extract(marker, detailsJson);
	}

	protected void stubFollowUp(String marker, String detailsJson) {
		classify(marker, EmailType.CLAIM_FOLLOW_UP);
		extract(marker, detailsJson);
	}
}
