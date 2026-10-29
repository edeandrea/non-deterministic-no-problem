package org.parasol.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimCategory;
import org.parasol.intake.ChatStubs;
import org.parasol.intake.IntakeScenario;
import org.parasol.intake.IntakeScenario.Mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * The review panel on the claim detail page, against the Compose GreenMail, a real {@code ClaimIntakeFlow} run and a
 * stubbed {@code claim-intake} model, since a claim only reaches {@code Pending Review} through a real waiting run. It shares
 * {@link UiTestProfile}, and so the boot, with the other Playwright tests.
 */
@QuarkusTest
@TestProfile(UiTestProfile.class)
class ClaimReviewPanelPageTests extends PlaywrightTests {
	private static final String SENDER = "jane.doe@example.com";
	private static final String SENDER_NAME = "Jane Doe";
	private static final String CLASSIFY = "Classify the following email";
	private static final String SUMMARY = "Summarize the following claim correspondence";
	private static final String SENTIMENT = "Assess the claimant's sentiment";
	private static final String DETAILS = "Extract the incident details from the following correspondence";
	private static final String COMPLETE_DETAILS =
		"""
		{"description":"Car hit a post.","incidentDate":"2026-10-07","incidentTime":null,"location":"Elm Street","category":"SINGLE_VEHICLE","policyNumber":null,"answeredItems":[]}""";
	private static final LocatorAssertions.IsVisibleOptions LONG_WAIT = new LocatorAssertions.IsVisibleOptions().setTimeout(20_000);

	@Inject
	IntakeScenario scenario;

	@Inject
	ObjectMapper objectMapper;

	WireMock wiremock;

	ChatStubs stubs;

	@BeforeEach
	void setUp() {
		this.stubs = new ChatStubs(this.wiremock, this.objectMapper);
		this.stubs.reset();
		this.scenario.reset();
		this.stubs.answer(SUMMARY, "A car hit a post on Elm Street.");
		this.stubs.answer(SENTIMENT, "Calm.");
	}

	@AfterEach
	void cleanUpIntake() {
		this.scenario.cleanUp();
	}

	@Test
	void statusLabelsAndFiltersCoverBothNewStatuses() {
		var pendingInformationClaim = createClaimWithStatus("Pending Information", "PI-000001");
		var pendingReviewClaim = createClaimWithStatus("Pending Review", "PR-000001");

		try {
			var page = loadPage("ClaimsList", "statusLabelsAndFiltersCoverBothNewStatuses");

			page.getByLabel("Filter by status").selectOption("Pending Review");
			assertThat(getTableBodyRows(page))
				.singleElement()
				.extracting(row -> row.getByRole(AriaRole.GRIDCELL).all().get(4).textContent())
				.isEqualTo("Pending Review");

			getTableBodyRows(page).get(0).getByText("Pending Review").hover();
			PlaywrightAssertions.assertThat(page.getByRole(AriaRole.TOOLTIP)).containsText("Intake is not complete");

			page.getByLabel("Filter by status").selectOption("Pending Information");
			assertThat(getTableBodyRows(page))
				.singleElement()
				.extracting(row -> row.getByRole(AriaRole.GRIDCELL).all().get(4).textContent())
				.isEqualTo("Pending Information");
		}
		finally {
			QuarkusTransaction.requiringNew().run(() -> {
				Claim.deleteById(pendingInformationClaim.id);
				Claim.deleteById(pendingReviewClaim.id);
			});
		}
	}

	@Test
	void theReviewControlsShowOnlyForAPendingReviewClaim() {
		var inProcessPage = loadPage(Claim.<Claim>findById(1L), "reviewControls_inProcess");
		assertNoReviewControls(inProcessPage);
		PlaywrightAssertions.assertThat(inProcessPage.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Edit"))).isVisible();

		var pendingInformationClaim = createClaimWithStatus("Pending Information", "PI-000002");

		try {
			assertNoReviewControls(loadPage(pendingInformationClaim, "reviewControls_pendingInformation"));
		}
		finally {
			QuarkusTransaction.requiringNew().run(() -> Claim.deleteById(pendingInformationClaim.id));
		}

		var reviewPage = loadPage(createPendingReviewClaim(), "reviewControls_pendingReview");

		PlaywrightAssertions.assertThat(reviewPage.getByTestId("review-panel")).isVisible();
		PlaywrightAssertions.assertThat(reviewPage.getByTestId("review-banner")).containsText("This claim is ready for your review.");
		PlaywrightAssertions.assertThat(submitButton(reviewPage)).isVisible();
		PlaywrightAssertions.assertThat(reviewPage.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Regenerate"))).isVisible();
		PlaywrightAssertions.assertThat(askCustomer(reviewPage, "location")).not().isChecked();
	}

	@Test
	void submittingTheEditedClaimMovesItInProcessAndRemovesTheControls() {
		var claim = createPendingReviewClaim();
		var page = loadPage(claim, "submittingTheEditedClaim");

		exact(page, "Location").fill("Oak Avenue");
		submitButton(page).click();

		PlaywrightAssertions.assertThat(page.getByText("In Process")).isVisible(LONG_WAIT);
		assertNoReviewControls(page);
		PlaywrightAssertions.assertThat(page.getByText("Oak Avenue")).isVisible();
		assertThat(this.scenario.claim(claim.id).location).isEqualTo("Oak Avenue");
	}

	@Test
	void askingTheCustomerMovesTheClaimToPendingInformation() {
		var claim = createPendingReviewClaim();
		var page = loadPage(claim, "askingTheCustomer");

		askCustomer(page, "location").check();
		askCustomer(page, "category").check();

		PlaywrightAssertions.assertThat(exact(page, "Location")).isDisabled();
		PlaywrightAssertions.assertThat(exact(page, "Category")).isDisabled();
		PlaywrightAssertions.assertThat(exact(page, "Description")).isEnabled();
		PlaywrightAssertions.assertThat(submitButton(page)).not().isVisible();

		page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Request more information")).click();

		PlaywrightAssertions.assertThat(page.getByText("Pending Information")).isVisible(LONG_WAIT);
		assertNoReviewControls(page);
		PlaywrightAssertions.assertThat(page.getByTestId("pending-information")).containsText("location");
		PlaywrightAssertions.assertThat(page.getByTestId("pending-information")).containsText("category");
	}

	@Test
	void regeneratingTheSentimentShowsTheNewAssessment() {
		var claim = createPendingReviewClaim();
		this.stubs.answer(SENTIMENT, "Anxious.");
		var page = loadPage(claim, "regeneratingTheSentiment");

		page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Regenerate")).click();

		PlaywrightAssertions.assertThat(page.getByTestId("review-sentiment")).hasText("Anxious.", new LocatorAssertions.HasTextOptions().setTimeout(20_000));
		PlaywrightAssertions.assertThat(page.getByTestId("review-panel")).isVisible();
	}

	@Test
	void aConflictReloadsTheReviewSoTheNextSubmitUsesTheCurrentVersion() {
		var claim = createPendingReviewClaim();
		var page = loadPage(claim, "aConflictReloadsTheReview");
		var reviewReads = new AtomicInteger();
		var conflicted = new AtomicBoolean();

		page.onRequest(request -> {
			if (request.method().equals("GET") && request.url().endsWith("/review")) {
				reviewReads.incrementAndGet();
			}
		});
		page.route("**/review-decision", route -> {
			if (conflicted.compareAndSet(false, true)) {
				route.fulfill(new Route.FulfillOptions()
					.setStatus(409)
					.setContentType("application/problem+json")
					.setBody("{\"status\":409,\"detail\":\"The review changed\"}"));
			}
			else {
				route.resume();
			}
		});

		submitButton(page).click();

		PlaywrightAssertions.assertThat(page.getByText("This claim changed; reloaded")).isVisible(LONG_WAIT);
		PlaywrightAssertions.assertThat(page.getByTestId("review-panel")).isVisible();
		assertThat(reviewReads).hasValue(1);

		submitButton(page).click();

		PlaywrightAssertions.assertThat(page.getByText("In Process")).isVisible(LONG_WAIT);
		assertNoReviewControls(page);
	}

	private static Locator exact(Page page, String label) {
		return page.getByLabel(label, new Page.GetByLabelOptions().setExact(true));
	}

	private static Locator askCustomer(Page page, String item) {
		return exact(page, "Ask customer for " + item);
	}

	private static Locator submitButton(Page page) {
		return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Submit claim for processing"));
	}

	private static void assertNoReviewControls(Page page) {
		PlaywrightAssertions.assertThat(page.getByTestId("review-panel")).not().isAttached();
		PlaywrightAssertions.assertThat(page.getByText("Ask customer")).hasCount(0);
		PlaywrightAssertions.assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Submit claim for processing"))).hasCount(0);
		PlaywrightAssertions.assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Request more information"))).hasCount(0);
		PlaywrightAssertions.assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Regenerate"))).hasCount(0);
	}

	private Claim createPendingReviewClaim() {
		var marker = marker();
		this.stubs.answerWhenAll(List.of(CLASSIFY, marker), "NEW_CLAIM");
		this.stubs.answerWhenAll(List.of(DETAILS, marker), COMPLETE_DETAILS);

		var messageId = this.scenario.submit(Mail.from(SENDER).name(SENDER_NAME).subject("Car accident").body("First email. " + marker));
		this.scenario.awaitFiled(messageId);
		var claim = this.scenario.awaitClaimOf(SENDER);
		this.scenario.awaitWaitingRun(claim.id);

		return claim;
	}

	private static Claim createClaimWithStatus(String status, String policyNumber) {
		return QuarkusTransaction.requiringNew().call(() -> {
			var claim = new Claim();
			claim.category = ClaimCategory.OTHER;
			claim.clientName = "Review Test Client";
			claim.policyNumber = policyNumber;
			claim.inceptionDate = LocalDate.of(2020, 1, 1);
			claim.incidentDate = LocalDate.of(2024, 6, 1);
			claim.status = status;
			claim.persistAndFlush();

			return claim;
		});
	}

	private static String marker() {
		return "[[%s]]".formatted(UUID.randomUUID());
	}

	// Rowgroup 1 is the header row, rowgroup 2 is the body rows (ClaimsListPageTests uses the same shape)
	private static List<Locator> getTableBodyRows(Page page) {
		var rowGroups = page.getByRole(AriaRole.GRID).getByRole(AriaRole.ROWGROUP).all();
		assertThat(rowGroups).hasSize(2);

		return rowGroups.get(1).getByRole(AriaRole.ROW).all();
	}

	private Page loadPage(Claim claim, String testName) {
		return loadPage("ClaimDetail/%d".formatted(claim.id), testName);
	}
}
