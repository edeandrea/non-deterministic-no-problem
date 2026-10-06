package org.parasol.claim.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimCategory;
import org.parasol.claim.model.ClaimImage;
import org.parasol.claim.model.ClaimImageContentType;
import org.parasol.claim.model.ClaimImageKind;
import org.parasol.claim.seed.ClaimImageSeeder.SeedImage;
import org.parasol.claim.seed.ClaimImageSeeder.SeedResult;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

// The startup run seeds the six sample claims; these tests only read those images. Tests that insert or delete images
// do it on a claim of their own, created and deleted around each test (deleting a claim deletes its images), so the
// shared seed data used by other test classes is never modified.
// src/test/resources/seed/claim-images/empty-for-tests.jpg is an empty, test-only resource for the empty-file case.
@QuarkusTest
class ClaimImageSeederTests {
	private static final long UNKNOWN_CLAIM_ID = -1L;

	@Inject
	ClaimImageSeeder seeder;

	private long testClaimId;

	@BeforeEach
	void createClaim() {
		this.testClaimId = QuarkusTransaction.requiringNew().call(() -> {
			var claim = new Claim();
			claim.category = ClaimCategory.OTHER;
			claim.inceptionDate = LocalDate.of(2020, 1, 1);
			claim.incidentDate = LocalDate.of(2024, 1, 1);
			claim.persist();

			return claim.id;
		});
	}

	@AfterEach
	void deleteClaim() {
		QuarkusTransaction.requiringNew().run(() -> Claim.deleteById(this.testClaimId));
	}

	@Test
	void startupSeedsOneOriginalAndOneProcessedImagePerSampleClaim() {
		var seededImages = QuarkusTransaction.requiringNew().call(() -> ClaimImageSeeder.SEED_IMAGES.stream()
			.map(SeedImage::claimId)
			.distinct()
			.flatMap(claimId -> ClaimImage.listForClaim(claimId).stream())
			.map(image -> new SeedImage(image.claim.id, image.kind, image.fileName))
			.toList());

		assertThat(seededImages)
			.containsExactlyInAnyOrderElementsOf(ClaimImageSeeder.SEED_IMAGES)
			.hasSize(12);
	}

	@Test
	void seededImagesAreTheResourceFilesAsJpeg() throws IOException {
		var expectedBytes = resourceBytes("original_car1.jpg");

		var image = QuarkusTransaction.requiringNew().call(() -> ClaimImage.listForClaim(1L).stream()
			.filter(i -> i.kind == ClaimImageKind.ORIGINAL)
			.findFirst()
			.map(i -> new StoredImage(i.fileName, i.contentType, i.data))
			.orElseThrow());

		assertThat(image)
			.usingRecursiveComparison()
			.isEqualTo(new StoredImage("original_car1.jpg", ClaimImageContentType.JPEG, expectedBytes));
	}

	@Test
	void seedingAgainInsertsNothing() {
		assertThat(this.seeder.seed(ClaimImageSeeder.SEED_IMAGES))
			.isEqualTo(new SeedResult(0, 12, 0, 0));
	}

	@Test
	void reinsertsAMissingImage() {
		var seedImages = List.of(new SeedImage(this.testClaimId, ClaimImageKind.ORIGINAL, "original_car1.jpg"));

		assertThat(this.seeder.seed(seedImages))
			.isEqualTo(new SeedResult(1, 0, 0, 0));

		assertThat(this.seeder.seed(seedImages))
			.isEqualTo(new SeedResult(0, 1, 0, 0));

		QuarkusTransaction.requiringNew().run(() -> ClaimImage.listForClaim(this.testClaimId).forEach(ClaimImage::delete));

		assertThat(this.seeder.seed(seedImages))
			.isEqualTo(new SeedResult(1, 0, 0, 0));

		assertThat(testClaimImageFileNames())
			.containsExactly("original_car1.jpg");
	}

	@Test
	void skipsAMissingClaimWithoutReadingItsFile() {
		// The file doesn't exist either: if the seeder tried to read it, the outcome would be IMAGE_UNREADABLE
		var seedImages = List.of(new SeedImage(UNKNOWN_CLAIM_ID, ClaimImageKind.ORIGINAL, "does-not-exist.jpg"));

		assertThat(this.seeder.seed(seedImages))
			.isEqualTo(new SeedResult(0, 0, 1, 0));
	}

	@Test
	void skipsAMissingFileAndSeedsTheRest() {
		var seedImages = List.of(
			new SeedImage(this.testClaimId, ClaimImageKind.ORIGINAL, "does-not-exist.jpg"),
			new SeedImage(this.testClaimId, ClaimImageKind.ORIGINAL, "original_car1.jpg"),
			new SeedImage(this.testClaimId, ClaimImageKind.PROCESSED, "car1-processed.jpg")
		);

		assertThat(this.seeder.seed(seedImages))
			.isEqualTo(new SeedResult(2, 0, 0, 1));

		assertThat(testClaimImageFileNames())
			.containsExactlyInAnyOrder("original_car1.jpg", "car1-processed.jpg");
	}

	@Test
	void skipsAnEmptyFileAndSeedsTheRest() {
		// An empty file would fail the @NotEmpty check at flush and roll back the whole run, so it's skipped up front
		var seedImages = List.of(
			new SeedImage(this.testClaimId, ClaimImageKind.ORIGINAL, "empty-for-tests.jpg"),
			new SeedImage(this.testClaimId, ClaimImageKind.ORIGINAL, "original_car1.jpg")
		);

		assertThat(this.seeder.seed(seedImages))
			.isEqualTo(new SeedResult(1, 0, 0, 1));

		assertThat(testClaimImageFileNames())
			.containsExactly("original_car1.jpg");
	}

	@Test
	void countsEveryOutcomeInOneRun() {
		var seedImages = List.of(
			new SeedImage(1L, ClaimImageKind.ORIGINAL, "original_car1.jpg"),
			new SeedImage(this.testClaimId, ClaimImageKind.ORIGINAL, "original_car1.jpg"),
			new SeedImage(UNKNOWN_CLAIM_ID, ClaimImageKind.ORIGINAL, "original_car1.jpg"),
			new SeedImage(this.testClaimId, ClaimImageKind.PROCESSED, "does-not-exist.jpg")
		);

		assertThat(this.seeder.seed(seedImages))
			.isEqualTo(new SeedResult(1, 1, 1, 1));
	}

	private List<String> testClaimImageFileNames() {
		return QuarkusTransaction.requiringNew().call(() -> ClaimImage.listForClaim(this.testClaimId).stream()
			.map(image -> image.fileName)
			.toList());
	}

	private static byte[] resourceBytes(String fileName) throws IOException {
		try (var stream = ClaimImageSeederTests.class.getResourceAsStream(ClaimImageSeeder.RESOURCE_DIRECTORY + fileName)) {
			assertThat(stream)
				.isNotNull();

			return stream.readAllBytes();
		}
	}

	private record StoredImage(String fileName, ClaimImageContentType contentType, byte[] data) {
	}
}