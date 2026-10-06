package org.parasol.claim.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import jakarta.validation.ConstraintViolationException;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

// Tests that only need a transaction use @TestTransaction, so everything they create is rolled back
// (ClaimsListPageTests expects exactly the 6 seeded claims). The cascade test needs real commits, and deletes its claim
// in a finally block.
@QuarkusTest
class ClaimImageTests {
	private static final byte[] IMAGE_DATA = { 1, 2, 3 };

	@Test
	@TestTransaction
	void dataColumnIsByteaNotOid() {
		var columnType = Claim.getEntityManager()
			.createNativeQuery("select data_type from information_schema.columns where table_name = 'claim_images' and column_name = 'data'", String.class)
			.getSingleResult();

		assertThat(columnType)
			.isEqualTo("bytea");
	}

	@Test
	@TestTransaction
	void storesAndListsImagesOldestFirst() {
		var claim = newClaim();
		var first = ClaimImage.store(claim, ClaimImageKind.ORIGINAL, "first.jpg", ClaimImageContentType.JPEG, IMAGE_DATA);
		var second = ClaimImage.store(claim, ClaimImageKind.PROCESSED, "second.png", ClaimImageContentType.PNG, IMAGE_DATA);
		ClaimImage.flush();

		assertThat(ClaimImage.listForClaim(claim.id))
			.extracting(image -> image.id)
			.containsExactly(first.id, second.id);
	}

	@Test
	@TestTransaction
	void listingDoesNotLoadTheImageBytes() {
		var claim = newClaim();
		ClaimImage.store(claim, ClaimImageKind.ORIGINAL, "photo.jpg", ClaimImageContentType.JPEG, IMAGE_DATA);
		ClaimImage.flush();
		ClaimImage.getEntityManager().clear();

		assertThat(ClaimImage.listForClaim(claim.id))
			.singleElement()
			.satisfies(image -> assertThat(Hibernate.isPropertyInitialized(image, "data")).isFalse());
	}

	@Test
	@TestTransaction
	void listingAnUnknownClaimFails() {
		assertThatThrownBy(() -> ClaimImage.listForClaim(-1L))
			.isInstanceOf(ClaimNotFoundException.class)
			.hasMessage("Claim -1 was not found")
			.extracting("claimId")
			.isEqualTo(-1L);
	}

	@Test
	@TestTransaction
	void findsAnImageOnlyThroughItsOwnClaim() {
		var claim = newClaim();
		var otherClaim = newClaim();
		var image = ClaimImage.store(claim, ClaimImageKind.ORIGINAL, "photo.jpg", ClaimImageContentType.JPEG, IMAGE_DATA);
		ClaimImage.flush();

		assertThat(ClaimImage.findForClaim(claim.id, image.id))
			.isSameAs(image);

		assertThatThrownBy(() -> ClaimImage.findForClaim(otherClaim.id, image.id))
			.isInstanceOf(ClaimImageNotFoundException.class)
			.hasMessage("Image %d was not found for claim %d", image.id, otherClaim.id);
	}

	@Test
	@TestTransaction
	void hasImageMatchesClaimKindAndFileName() {
		var claim = newClaim();
		ClaimImage.store(claim, ClaimImageKind.ORIGINAL, "photo.jpg", ClaimImageContentType.JPEG, IMAGE_DATA);
		ClaimImage.flush();

		assertThat(ClaimImage.hasImage(claim, ClaimImageKind.ORIGINAL, "photo.jpg"))
			.isTrue();

		assertThat(ClaimImage.hasImage(claim, ClaimImageKind.PROCESSED, "photo.jpg"))
			.isFalse();

		assertThat(ClaimImage.hasImage(claim, ClaimImageKind.ORIGINAL, "other.jpg"))
			.isFalse();
	}

	@Test
	@TestTransaction
	void emptyImageDataIsRejected() {
		var claim = newClaim();
		ClaimImage.store(claim, ClaimImageKind.ORIGINAL, "empty.jpg", ClaimImageContentType.JPEG, new byte[0]);

		// A lambda, not ClaimImage::flush: Panache only enhances direct calls to its static methods
		assertThatThrownBy(() -> ClaimImage.flush())
			.isInstanceOf(ConstraintViolationException.class)
			.hasMessageContaining("data");
	}

	@Test
	@TestTransaction
	void blankFileNameIsRejected() {
		var claim = newClaim();
		ClaimImage.store(claim, ClaimImageKind.ORIGINAL, " ", ClaimImageContentType.JPEG, IMAGE_DATA);

		assertThatThrownBy(() -> ClaimImage.flush())
			.isInstanceOf(ConstraintViolationException.class)
			.hasMessageContaining("fileName");
	}

	@Test
	void deletingAClaimDeletesItsImages() {
		var ids = QuarkusTransaction.requiringNew().call(() -> {
			var claim = newClaim();
			var image = ClaimImage.store(claim, ClaimImageKind.ORIGINAL, "photo.jpg", ClaimImageContentType.JPEG, IMAGE_DATA);

			return new StoredImage(claim.id, image.id);
		});

		try {
			QuarkusTransaction.requiringNew().run(() -> Claim.deleteById(ids.claimId()));

			assertThat(QuarkusTransaction.requiringNew().call(() -> ClaimImage.findByIdOptional(ids.imageId())))
				.isEmpty();
		}
		finally {
			QuarkusTransaction.requiringNew().run(() -> Claim.deleteById(ids.claimId()));
		}
	}

	private record StoredImage(long claimId, long imageId) {
	}

	private static Claim newClaim() {
		var claim = new Claim();
		claim.category = ClaimCategory.OTHER;
		claim.inceptionDate = LocalDate.of(2020, 1, 1);
		claim.incidentDate = LocalDate.of(2024, 1, 1);
		claim.persistAndFlush();

		return claim;
	}
}