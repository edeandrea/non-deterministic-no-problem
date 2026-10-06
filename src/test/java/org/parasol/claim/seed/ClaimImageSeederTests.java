package org.parasol.claim.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.parasol.claim.seed.ClaimImageSeeder.SeedImageNotFoundException;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

// The startup run seeds the six sample claims; these tests only read those images. Tests that insert or delete images
// do it on a claim of their own, created and deleted around each test (deleting a claim deletes its images), so the
// shared seed data used by other test classes is never modified.
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
			.isZero();
	}

	@Test
	void reinsertsAMissingImage() {
		var seedImages = List.of(new SeedImage(this.testClaimId, ClaimImageKind.ORIGINAL, "original_car1.jpg"));

		assertThat(this.seeder.seed(seedImages))
			.isOne();

		assertThat(this.seeder.seed(seedImages))
			.isZero();

		QuarkusTransaction.requiringNew().run(() -> ClaimImage.listForClaim(this.testClaimId).forEach(ClaimImage::delete));

		assertThat(this.seeder.seed(seedImages))
			.isOne();

		assertThat(QuarkusTransaction.requiringNew().call(() -> ClaimImage.listForClaim(this.testClaimId)))
			.singleElement()
			.extracting(image -> image.fileName)
			.isEqualTo("original_car1.jpg");
	}

	@Test
	void skipsAMissingClaimWithoutReadingItsFile() {
		// The file doesn't exist either: if the seeder tried to read it, the call would throw
		var seedImages = List.of(new SeedImage(UNKNOWN_CLAIM_ID, ClaimImageKind.ORIGINAL, "does-not-exist.jpg"));

		assertThat(this.seeder.seed(seedImages))
			.isZero();
	}

	@Test
	void missingResourceFileFails() {
		var seedImages = List.of(new SeedImage(this.testClaimId, ClaimImageKind.ORIGINAL, "does-not-exist.jpg"));

		assertThatThrownBy(() -> this.seeder.seed(seedImages))
			.isInstanceOf(SeedImageNotFoundException.class)
			.hasMessage("Seed image resource /seed/claim-images/does-not-exist.jpg was not found");
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