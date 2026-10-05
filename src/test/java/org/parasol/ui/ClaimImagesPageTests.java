package org.parasol.ui;

import static io.restassured.RestAssured.get;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimCategory;
import org.parasol.claim.model.ClaimImage;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;
import io.quarkiverse.quinoa.testing.QuinoaTestProfiles;

@QuarkusTest
@TestProfile(QuinoaTestProfiles.EnableAndRunTests.class)
public class ClaimImagesPageTests extends PlaywrightTests {
	private Long testClaimId;

	@AfterEach
	void cleanUp() {
		if (testClaimId != null) {
			QuarkusTransaction.requiringNew().run(() -> {
				ClaimImage.delete("claim.id = ?1", testClaimId);
				Claim.deleteById(testClaimId);
			});
		}
	}

	@Test
	void seededClaimImagesAreLoadedFromTheApi() {
		var claim = Claim.<Claim>findByIdOptional(1L)
			.orElseThrow();
		var imageMetadata = get("/api/db/claims/{id}/images", claim.id)
			.then()
			.statusCode(200)
			.extract()
			.jsonPath()
			.getList(".", Map.class);
		var imageUrls = imageMetadata.stream()
			.map(image -> (String) image.get("url"))
			.toList();

		var page = loadPage("ClaimDetail/%d".formatted(claim.id), "%s_seededClaimImages".formatted(getClass().getSimpleName()));
		page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Documents")).click();

		assertThat(imageMetadata)
			.extracting(image -> image.get("kind"))
			.containsExactlyInAnyOrder("ORIGINAL", "PROCESSED");
		imageUrls.forEach(imageUrl ->
			assertThat(page.locator("img.image-gallery-image[src='%s']".formatted(imageUrl)).count())
				.isPositive()
		);
	}

	@Test
	void claimWithoutImagesShowsEmptyMessagesInBothPanels() {
		testClaimId = QuarkusTransaction.requiringNew().call(() -> {
			var claim = new Claim();
			claim.category = ClaimCategory.OTHER;
			claim.inceptionDate = LocalDate.of(2020, 1, 1);
			claim.incidentDate = LocalDate.of(2024, 1, 1);
			claim.persistAndFlush();

			return claim.id;
		});

		var page = loadPage("ClaimDetail/%d".formatted(testClaimId), "%s_claimWithoutImages".formatted(getClass().getSimpleName()));
		page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Documents")).click();

		assertThat(page.getByText("No images attached").all())
			.hasSize(2);
		PlaywrightAssertions.assertThat(page.getByText("No images attached").first())
			.isVisible();
		PlaywrightAssertions.assertThat(page.getByText("No images attached").last())
			.isVisible();
	}

	@Test
	void originalAppRouteIsGone() {
		var page = loadPage("OriginalApp", "%s_originalAppRouteIsGone".formatted(getClass().getSimpleName()));

		PlaywrightAssertions.assertThat(page.getByText("404 Page not found"))
			.isVisible();
	}
}
