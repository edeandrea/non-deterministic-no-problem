package org.parasol.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.parasol.claim.model.Claim;
import org.parasol.intake.ChatStubs;
import org.parasol.intake.IntakeScenario;
import org.parasol.intake.mailbox.InboundEmail;
import org.parasol.intake.mailbox.InboxWatcherControl;
import org.parasol.intake.mailbox.MailFolder;
import org.parasol.testing.mail.GreenMailMailbox;
import org.parasol.testing.mail.Roundcube;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * The whole demo, end to end: a seeded claimant writes to the claims address from the real Roundcube, the real IMAP IDLE
 * watcher picks the email up, a real {@code ClaimIntakeFlow} run creates the claim (with the {@code claim-intake} model on
 * WireMock), the claimant reads the replies in Roundcube, and a claims processor decides the review in the Parasol UI.
 * <p>
 *   Roundcube is the Compose Dev Service from compose-devservices.yml, on a random port, which {@link UiTestProfile} turns on
 *   for every Playwright boot. {@code %test} keeps the intake off, so each test starts the application's own watcher and
 *   stops it again ({@link InboxWatcherControl}), rather than having a test profile of its own.
 * </p>
 */
@QuarkusTest
@TestProfile(UiTestProfile.class)
class RoundcubeClaimIntakeE2ETests extends PlaywrightTests {
	private static final String CLAIMANT = "marty.mcfly@email.com";
	private static final String CLAIMANT_NAME = "Marty McFly";
	private static final String CLASSIFY = "Classify the following email";
	private static final String SUMMARY = "Summarize the following claim correspondence";
	private static final String SENTIMENT = "Assess the claimant's sentiment";
	private static final String DETAILS = "Extract the incident details from the following correspondence";
	private static final String SUMMARY_TEXT = "Marty's car hit a lamp post on Elm Street.";
	private static final String FINAL_REVIEW = "A claims processor will do a final check of your claim";
	private static final String MISSING_INFORMATION = "we need the following details";
	private static final String IN_PROCESS = "Great news!";
	private static final LocatorAssertions.IsVisibleOptions LONG_WAIT = new LocatorAssertions.IsVisibleOptions().setTimeout(20_000);

	@Inject
	IntakeScenario scenario;

	@Inject
	InboxWatcherControl watcher;

	@Inject
	GreenMailMailbox greenMail;

	@Inject
	ObjectMapper objectMapper;

	@ConfigProperty(name = "quarkus.mailer.host")
	String mailHost;

	@ConfigProperty(name = "parasol.mail.roundcube-port")
	int roundcubePort;

	@ConfigProperty(name = "parasol.intake.address")
	String claimsAddress;

	WireMock wiremock;

	ChatStubs stubs;

	@BeforeEach
	void setUp() {
		this.stubs = new ChatStubs(this.wiremock, this.objectMapper);
		this.stubs.reset();
		// The watcher is off here, so removing the claims user (and its folders) can't pull a mailbox from under its IDLE
		this.scenario.reset();
		this.stubs.answer(SUMMARY, SUMMARY_TEXT);
		this.stubs.answer(SENTIMENT, "Calm.");
		this.watcher.start();
	}

	@AfterEach
	void tearDown() {
		try {
			this.watcher.stop();

			// Every scenario ends with the claim in process, so its runs have ended and Flow has removed their rows
			await()
				.atMost(IntakeScenario.WAIT)
				.untilAsserted(() -> assertThat(List.of(this.scenario.instanceRows(), this.scenario.taskRows()))
					.as("Flow instance and task rows of the intake runs")
					.containsOnly(0L));
		}
		finally {
			this.scenario.cleanUp();
			this.greenMail.purge();
		}
	}

	@Test
	void aCompleteClaimGoesFromRoundcubeToInProcess() {
		var marker = marker();
		stubNewClaim(marker, details("2026-10-07", "Elm Street", "SINGLE_VEHICLE", List.of()));
		var roundcube = roundcube("aCompleteClaim_roundcube");

		roundcube.send(this.claimsAddress, "Car accident on Elm Street", "My car hit a lamp post this morning. " + marker, photo());

		var claim = this.scenario.awaitNewClaimOf(CLAIMANT);
		await()
			.atMost(IntakeScenario.WAIT)
			.until(() -> this.scenario.inFolder(MailFolder.PROCESSED), emails -> !emails.isEmpty());
		assertThat(this.scenario.inFolder(MailFolder.PROCESSED))
			.singleElement()
			.returns(Optional.of(CLAIMANT), InboundEmail::fromAddress)
			.returns(Optional.of(CLAIMANT_NAME), InboundEmail::fromName);
		this.scenario.awaitWaitingRun(claim.id);
		assertThat(this.scenario.claim(claim.id))
			.returns("Pending Review", c -> c.status)
			.returns(CLAIMANT_NAME, c -> c.clientName);

		var page = openFromClaimsList(claim, "aCompleteClaim_parasol");
		PlaywrightAssertions.assertThat(page.getByTestId("review-banner")).containsText("This claim is ready for your review.");
		PlaywrightAssertions.assertThat(exact(page, "Description")).hasValue(SUMMARY_TEXT);
		PlaywrightAssertions.assertThat(page.getByTestId("review-sentiment")).hasText("Calm.");
		tab(page, "Documents").click();
		PlaywrightAssertions.assertThat(page.locator("img.image-gallery-image[alt='crash-photo.jpg']").first()).isVisible();
		tab(page, "Summary").click();

		var claimSubject = "[%s]".formatted(claim.claimNumber);
		roundcube.awaitMessages(claimSubject, 1);
		PlaywrightAssertions.assertThat(roundcube.openNewest(claimSubject))
			.containsText("Dear %s".formatted(CLAIMANT_NAME));
		PlaywrightAssertions.assertThat(roundcube.openNewest(claimSubject)).containsText(FINAL_REVIEW);

		submitButton(page).click();

		PlaywrightAssertions.assertThat(page.getByText("In Process")).isVisible(LONG_WAIT);
		roundcube.awaitMessages(claimSubject, 2);
		PlaywrightAssertions.assertThat(roundcube.openNewest(claimSubject)).containsText(IN_PROCESS);
	}

	@Test
	void aClaimWithoutALocationIsCompletedByTheClaimantsReply() {
		var first = marker();
		stubNewClaim(first, details("2026-10-07", null, "SINGLE_VEHICLE", List.of()));
		var roundcube = roundcube("aClaimWithoutALocation_roundcube");

		roundcube.send(this.claimsAddress, "My car was damaged", "Someone backed into my car on the 7th. " + first, null);

		var claim = this.scenario.awaitNewClaimOf(CLAIMANT);
		awaitStatus(claim, "Pending Information");
		var claimSubject = "[%s]".formatted(claim.claimNumber);
		roundcube.awaitMessages(claimSubject, 1);
		var missing = roundcube.openNewest(claimSubject);
		PlaywrightAssertions.assertThat(missing).containsText(MISSING_INFORMATION);
		PlaywrightAssertions.assertThat(missing.locator("li")).hasText(new String[] { "location" });

		var second = marker();
		stubFollowUp(second, details("2026-10-07", "Elm Street", "SINGLE_VEHICLE", List.of("LOCATION")));
		roundcube.reply("It happened on Elm Street. " + second);

		awaitStatus(claim, "Pending Review");
		this.scenario.awaitWaitingRun(claim.id);
		roundcube.awaitMessages(claimSubject, 2);
		PlaywrightAssertions.assertThat(roundcube.openNewest(claimSubject)).containsText(FINAL_REVIEW);

		var page = loadPage("ClaimDetail/%d".formatted(claim.id), "aClaimWithoutALocation_parasol");
		PlaywrightAssertions.assertThat(exact(page, "Location")).hasValue("Elm Street");
		submitButton(page).click();

		PlaywrightAssertions.assertThat(page.getByText("In Process")).isVisible(LONG_WAIT);
		roundcube.awaitMessages(claimSubject, 3);
		PlaywrightAssertions.assertThat(roundcube.openNewest(claimSubject)).containsText(IN_PROCESS);
	}

	@Test
	void theReviewerAsksForTheCategoryAndTheClaimantsReplyCompletesTheClaim() {
		var first = marker();
		stubNewClaim(first, details("2026-10-07", "Elm Street", "SINGLE_VEHICLE", List.of()));
		var roundcube = roundcube("theReviewerAsks_roundcube");

		roundcube.send(this.claimsAddress, "Accident report", "My car hit a lamp post on Elm Street on the 7th. " + first, null);

		var claim = this.scenario.awaitNewClaimOf(CLAIMANT);
		this.scenario.awaitWaitingRun(claim.id);
		var page = loadPage("ClaimDetail/%d".formatted(claim.id), "theReviewerAsks_parasol");

		exact(page, "Ask customer for category").check();
		page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Request more information")).click();

		PlaywrightAssertions.assertThat(page.getByText("Pending Information")).isVisible(LONG_WAIT);
		var claimSubject = "[%s]".formatted(claim.claimNumber);
		// The final-review reply, then the reviewer's request
		roundcube.awaitMessages(claimSubject, 2);
		var request = roundcube.openNewest(claimSubject);
		PlaywrightAssertions.assertThat(request).containsText(MISSING_INFORMATION);
		PlaywrightAssertions.assertThat(request.locator("li")).hasText(new String[] { "category" });

		var second = marker();
		stubFollowUp(second, details("2026-10-07", "Elm Street", "SINGLE_VEHICLE", List.of("CATEGORY")));
		roundcube.reply("Only my car was involved. " + second);

		awaitStatus(claim, "Pending Review");
		this.scenario.awaitWaitingRun(claim.id);
		page.reload();
		submitButton(page).click();

		PlaywrightAssertions.assertThat(page.getByText("In Process")).isVisible(LONG_WAIT);
	}

	private Roundcube roundcube(String testName) {
		return Roundcube.loginAs(newPage(testName), "http://%s:%d".formatted(this.mailHost, this.roundcubePort), CLAIMANT);
	}

	// Finds the new claim in the list among the Pending Review ones, and opens it from there
	private Page openFromClaimsList(Claim claim, String testName) {
		var page = loadPage("ClaimsList", testName);
		page.getByLabel("Filter by status").selectOption("Pending Review");
		page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(claim.claimNumber)).click();
		PlaywrightAssertions.assertThat(page.getByTestId("review-panel")).isVisible(LONG_WAIT);

		return page;
	}

	private void awaitStatus(Claim claim, String status) {
		await()
			.atMost(IntakeScenario.WAIT)
			.until(() -> this.scenario.claim(claim.id).status, status::equals);
	}

	private void stubNewClaim(String marker, String detailsJson) {
		this.stubs.answerWhenAll(List.of(CLASSIFY, marker), "NEW_CLAIM");
		this.stubs.answerWhenAll(List.of(DETAILS, marker), detailsJson);
	}

	private void stubFollowUp(String marker, String detailsJson) {
		this.stubs.answerWhenAll(List.of(CLASSIFY, marker), "CLAIM_FOLLOW_UP");
		this.stubs.answerWhenAll(List.of(DETAILS, marker), detailsJson);
	}

	private static String details(String date, String location, String category, List<String> answered) {
		return """
			{"incidentDescribed":true,"incidentDate":%s,"incidentTime":null,"location":%s,"category":%s,"policyNumber":null,"answeredItems":%s}"""
			.formatted(quote(date), quote(location), quote(category), answered.stream().map(RoundcubeClaimIntakeE2ETests::quote).toList());
	}

	private static String quote(String value) {
		return (value == null) ? "null" : "\"%s\"".formatted(value);
	}

	private static String marker() {
		return "[[%s]]".formatted(UUID.randomUUID());
	}

	// One of the seeded claim photos, under the name the claimant attaches it as
	private static Path photo() {
		try (var image = RoundcubeClaimIntakeE2ETests.class.getResourceAsStream("/seed/claim-images/car1-processed.jpg")) {
			var photo = Files.createTempDirectory("roundcube-e2e").resolve("crash-photo.jpg");
			Files.copy(image, photo);
			photo.toFile().deleteOnExit();

			return photo;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static Locator exact(Page page, String label) {
		return page.getByLabel(label, new Page.GetByLabelOptions().setExact(true));
	}

	private static Locator tab(Page page, String name) {
		return page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName(name));
	}

	private static Locator submitButton(Page page) {
		return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Submit claim for processing"));
	}
}