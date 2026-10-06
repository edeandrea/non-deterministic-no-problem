package org.parasol.ui;

import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimCategory;
import org.parasol.claim.model.ClaimImage;
import org.parasol.claim.model.ClaimImageKind;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;
import io.quarkiverse.quinoa.testing.QuinoaTestProfiles;

// The claim without images is created for the test and deleted afterwards (ClaimsListPageTests expects exactly the 6
// seeded claims). The seeded claim's images come from ClaimImageSeeder.
@QuarkusTest
@TestProfile(QuinoaTestProfiles.EnableAndRunTests.class)
public class ClaimImagesPageTests extends PlaywrightTests {
	private static final long SEEDED_CLAIM_ID = 1L;
	private static final long UNKNOWN_CLAIM_ID = 999_999L;

	private Long testClaimId;

	@AfterEach
	void deleteTestClaim() {
		if (this.testClaimId != null) {
			QuarkusTransaction.requiringNew().run(() -> Claim.deleteById(this.testClaimId));
		}
	}

	@Test
	void seededClaimShowsItsImagesFromTheApi() {
		var originalUrl = imageUrl(SEEDED_CLAIM_ID, ClaimImageKind.ORIGINAL);
		var processedUrl = imageUrl(SEEDED_CLAIM_ID, ClaimImageKind.PROCESSED);
		var page = loadPage("ClaimDetail/%d".formatted(SEEDED_CLAIM_ID), "%s_seededClaimShowsItsImagesFromTheApi".formatted(getClass().getSimpleName()));

		// The right-hand panel shows the processed image
		PlaywrightAssertions.assertThat(galleryImage(page, processedUrl))
			.isVisible();

		openAttachedImages(page);

		// The Documents tab shows the original image
		PlaywrightAssertions.assertThat(galleryImage(page, originalUrl))
			.isVisible();

		PlaywrightAssertions.assertThat(page.getByText("No images attached"))
			.hasCount(0);
	}

	@Test
	void claimWithoutImagesSaysSoInBothPlaces() {
		this.testClaimId = QuarkusTransaction.requiringNew().call(() -> {
			var claim = new Claim();
			claim.category = ClaimCategory.OTHER;
			claim.inceptionDate = LocalDate.of(2020, 1, 1);
			claim.incidentDate = LocalDate.of(2024, 1, 1);
			claim.persist();

			return claim.id;
		});

		var page = loadPage("ClaimDetail/%d".formatted(this.testClaimId), "%s_claimWithoutImagesSaysSoInBothPlaces".formatted(getClass().getSimpleName()));
		openAttachedImages(page);

		PlaywrightAssertions.assertThat(page.getByText("No images attached"))
			.hasCount(2);

		PlaywrightAssertions.assertThat(page.locator("img.image-gallery-image"))
			.hasCount(0);
	}

	@Test
	void unknownClaimSaysTheClaimDoesNotExist() {
		var page = loadPage("ClaimDetail/%d".formatted(UNKNOWN_CLAIM_ID), "%s_unknownClaimSaysTheClaimDoesNotExist".formatted(getClass().getSimpleName()));
		var notFound = page.getByTestId("claim-not-found");

		PlaywrightAssertions.assertThat(notFound.getByRole(AriaRole.HEADING, new Locator.GetByRoleOptions().setName("Claim not found")))
			.isVisible();

		PlaywrightAssertions.assertThat(notFound)
			.containsText("There's no claim with id %d".formatted(UNKNOWN_CLAIM_ID));

		// None of the claim detail page is rendered
		PlaywrightAssertions.assertThat(page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Documents")))
			.hasCount(0);

		notFound.getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName("Back to claims")).click();

		PlaywrightAssertions.assertThat(page)
			.hasURL(getUrl("ClaimsList"));
	}

	@Test
	void originalAppRouteIsGone() {
		var page = loadPage("OriginalApp", "%s_originalAppRouteIsGone".formatted(getClass().getSimpleName()));

		PlaywrightAssertions.assertThat(page.getByText("404 Page not found"))
			.isVisible();
	}

	// The "Original claim content" accordion starts expanded, so its attached images show once the tab is open
	private static void openAttachedImages(Page page) {
		page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Documents")).click();

		PlaywrightAssertions.assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Original claim content")))
			.hasAttribute("aria-expanded", "true");
	}

	// The gallery renders the image's root-relative API path resolved against the backend origin
	private static Locator galleryImage(Page page, String imageUrl) {
		return page.locator("img.image-gallery-image")
			.and(page.locator("[src$='%s']".formatted(imageUrl)));
	}

	private static String imageUrl(long claimId, ClaimImageKind kind) {
		var imageId = QuarkusTransaction.requiringNew().call(() -> ClaimImage.listForClaim(claimId).stream()
			.filter(image -> image.kind == kind)
			.findFirst()
			.map(image -> image.id)
			.orElseThrow(() -> new IllegalStateException("Seeded claim %d has no %s image".formatted(claimId, kind))));

		return "/api/db/claims/%d/images/%d".formatted(claimId, imageId);
	}
}