package org.parasol.claim.rest;

import static io.restassured.RestAssured.get;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response.Status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimCategory;
import org.parasol.claim.model.ClaimImageKind;
import org.parasol.claim.persistence.ClaimImageRepository;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ClaimImageResourceTests {
	private static final byte[] IMAGE_DATA = { 0, 1, 2, 3, (byte) 255 };

	@Inject
	ClaimImageRepository claimImageRepository;

	private long claimId;
	private long otherClaimId;

	@BeforeEach
	void setUp() {
		claimId = createClaim();
	}

	@AfterEach
	void cleanUp() {
		QuarkusTransaction.requiringNew().run(() -> {
			claimImageRepository.delete("claim.id = ?1", claimId);
			Claim.deleteById(claimId);
			if (otherClaimId != 0) {
				claimImageRepository.delete("claim.id = ?1", otherClaimId);
				Claim.deleteById(otherClaimId);
			}
		});
	}

	@Test
	void listsImagesWithoutBinaryDataAndWithImageUrls() {
		var firstImage = storeImage(ClaimImageKind.ORIGINAL, "original.jpg", "image/jpeg", IMAGE_DATA);
		var secondImage = storeImage(ClaimImageKind.PROCESSED, "processed.png", "image/png", IMAGE_DATA);

		var images = get("/api/db/claims/{id}/images", claimId).then()
			.statusCode(Status.OK.getStatusCode())
			.contentType("application/json")
			.extract()
			.jsonPath()
			.getList(".", Map.class);

		assertThat(images)
			.hasSize(2)
			.satisfiesExactly(
				metadata -> assertThat(metadata)
					.containsEntry("id", Math.toIntExact(firstImage))
					.containsEntry("kind", "ORIGINAL")
					.containsEntry("file_name", "original.jpg")
					.containsEntry("content_type", "image/jpeg")
					.containsEntry("url", "http://localhost:8081/api/db/claims/%d/images/%d".formatted(claimId, firstImage))
					.doesNotContainKey("data"),
				metadata -> assertThat(metadata)
					.containsEntry("id", Math.toIntExact(secondImage))
					.containsEntry("kind", "PROCESSED")
					.containsEntry("file_name", "processed.png")
					.containsEntry("content_type", "image/png")
					.doesNotContainKey("data")
			);
	}

	@Test
	void returnsEmptyListWhenClaimHasNoImages() {
		var images = get("/api/db/claims/{id}/images", claimId).then()
			.statusCode(Status.OK.getStatusCode())
			.extract()
			.jsonPath()
			.getList(".");

		assertThat(images)
			.isEmpty();
	}

	@Test
	void publishesImageEndpointsInOpenApi() {
		var paths = get("/q/openapi?format=json").then()
			.statusCode(Status.OK.getStatusCode())
			.extract()
			.jsonPath()
			.getMap("paths");

		assertThat(paths)
			.containsKeys(
				"/api/db/claims/{id}/images",
				"/api/db/claims/{id}/images/{imageId}"
			);
	}

	@Test
	void storesAndReturnsImageBytesWithContentType() {
		var imageId = storeImage(ClaimImageKind.ORIGINAL, "photo.png", "image/png", IMAGE_DATA);

		var response = get("/api/db/claims/{id}/images/{imageId}", claimId, imageId).then()
			.statusCode(Status.OK.getStatusCode())
			.contentType("image/png")
			.extract()
			.response();

		assertThat(response.asByteArray())
			.containsExactly(IMAGE_DATA);
	}

	@Test
	void returnsProblemDetailsForUnknownClaim() {
		var response = get("/api/db/claims/{id}/images", 9_999_999).then()
			.statusCode(Status.NOT_FOUND.getStatusCode())
			.contentType("application/problem+json")
			.extract()
			.response();

		assertThat(response.jsonPath().getMap("."))
			.containsEntry("type", "about:blank")
			.containsEntry("title", "Not Found")
			.containsEntry("status", 404)
			.containsEntry("detail", "Claim 9999999 was not found");

		var existingImageId = claimImageRepository.findAll()
			.firstResultOptional()
			.orElseThrow()
			.id;
		var unknownClaimImageResponse = get("/api/db/claims/{id}/images/{imageId}", 9_999_999, existingImageId).then()
			.statusCode(Status.NOT_FOUND.getStatusCode())
			.contentType("application/problem+json")
			.extract()
			.response();

		assertThat(unknownClaimImageResponse.jsonPath().getInt("status"))
			.isEqualTo(Status.NOT_FOUND.getStatusCode());
	}

	@Test
	void returnsProblemDetailsForUnknownOrForeignImage() {
		var imageId = storeImage(ClaimImageKind.ORIGINAL, "photo.png", "image/png", IMAGE_DATA);
		otherClaimId = createClaim();

		List.of(
			get("/api/db/claims/{id}/images/{imageId}", claimId, 9_999_999),
			get("/api/db/claims/{id}/images/{imageId}", otherClaimId, imageId)
		).forEach(response -> {
			assertThat(response.statusCode())
				.isEqualTo(Status.NOT_FOUND.getStatusCode());
			assertThat(response.getContentType())
				.startsWith("application/problem+json");
		});
	}

	private long createClaim() {
		return QuarkusTransaction.requiringNew().call(() -> {
			var claim = new Claim();
			claim.category = ClaimCategory.OTHER;
			claim.inceptionDate = LocalDate.of(2020, 1, 1);
			claim.incidentDate = LocalDate.of(2024, 1, 1);
			claim.persistAndFlush();

			return claim.id;
		});
	}

	private long storeImage(ClaimImageKind kind, String fileName, String contentType, byte[] data) {
		return QuarkusTransaction.requiringNew().call(() ->
			claimImageRepository.storeImage(Claim.findById(claimId), kind, fileName, contentType, data).id
		);
	}
}
