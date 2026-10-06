package org.parasol.claim.rest;

import static io.restassured.RestAssured.get;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;

import jakarta.ws.rs.core.Response.Status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimCategory;
import org.parasol.claim.model.ClaimImage;
import org.parasol.claim.model.ClaimImageContentType;
import org.parasol.claim.model.ClaimImageKind;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

import io.restassured.http.ContentType;
import io.restassured.response.Response;

// Not @TestTransaction: REST Assured calls the endpoint over HTTP, so it runs on another thread in its own transaction
// and can't see the test's uncommitted rows (a claim persisted in a @TestTransaction is a 404 to the endpoint). Fixtures
// are therefore committed with QuarkusTransaction.requiringNew() and deleted afterwards (ClaimsListPageTests expects
// exactly the 6 seeded claims). Deleting a claim deletes its images.
@QuarkusTest
class ClaimImageResourceTests {
	private static final byte[] JPEG_DATA = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 1, 2 };
	private static final byte[] PNG_DATA = { (byte) 0x89, 'P', 'N', 'G', 3, 4 };
	private static final long UNKNOWN_ID = -1L;

	private long claimId;
	private long otherClaimId;

	@BeforeEach
	void createClaims() {
		this.claimId = createClaim();
		this.otherClaimId = createClaim();
	}

	@AfterEach
	void deleteClaims() {
		QuarkusTransaction.requiringNew().run(() -> {
			Claim.deleteById(this.claimId);
			Claim.deleteById(this.otherClaimId);
		});
	}

	@Test
	void listsImageMetadataOldestFirstWithoutBytes() {
		var original = storeImage(this.claimId, ClaimImageKind.ORIGINAL, "original.jpg", ClaimImageContentType.JPEG, JPEG_DATA);
		var processed = storeImage(this.claimId, ClaimImageKind.PROCESSED, "processed.png", ClaimImageContentType.PNG, PNG_DATA);

		var images = get("/api/db/claims/{id}/images", this.claimId).then()
			.statusCode(Status.OK.getStatusCode())
			.contentType(ContentType.JSON)
			.extract()
			.jsonPath()
			.getList(".", Map.class);

		assertThat(images)
			.containsExactly(
				Map.of(
					"id", (int) original,
					"kind", "ORIGINAL",
					"file_name", "original.jpg",
					"content_type", "image/jpeg",
					"url", "/api/db/claims/%d/images/%d".formatted(this.claimId, original)
				),
				Map.of(
					"id", (int) processed,
					"kind", "PROCESSED",
					"file_name", "processed.png",
					"content_type", "image/png",
					"url", "/api/db/claims/%d/images/%d".formatted(this.claimId, processed)
				)
			);
	}

	@Test
	void listsNothingForAClaimWithoutImages() {
		var images = get("/api/db/claims/{id}/images", this.claimId).then()
			.statusCode(Status.OK.getStatusCode())
			.contentType(ContentType.JSON)
			.extract()
			.jsonPath()
			.getList(".");

		assertThat(images)
			.isEmpty();
	}

	@Test
	void returnsStoredBytesWithTheirContentType() {
		var imageId = storeImage(this.claimId, ClaimImageKind.ORIGINAL, "photo.png", ClaimImageContentType.PNG, PNG_DATA);

		var response = get("/api/db/claims/{id}/images/{imageId}", this.claimId, imageId).then()
			.statusCode(Status.OK.getStatusCode())
			.contentType("image/png")
			.header("X-Content-Type-Options", "nosniff")
			.extract()
			.response();

		assertThat(response.asByteArray())
			.containsExactly(PNG_DATA);
	}

	@Test
	void listingAnUnknownClaimIsNotFound() {
		var path = "/api/db/claims/%d/images".formatted(UNKNOWN_ID);

		assertProblem(get(path), "Claim %d was not found".formatted(UNKNOWN_ID), path);
	}

	@Test
	void imageOfAnUnknownClaimIsNotFound() {
		var imageId = storeImage(this.claimId, ClaimImageKind.ORIGINAL, "photo.jpg", ClaimImageContentType.JPEG, JPEG_DATA);
		var path = "/api/db/claims/%d/images/%d".formatted(UNKNOWN_ID, imageId);

		assertProblem(get(path), "Image %d was not found for claim %d".formatted(imageId, UNKNOWN_ID), path);
	}

	@Test
	void unknownImageIsNotFound() {
		var path = "/api/db/claims/%d/images/%d".formatted(this.claimId, UNKNOWN_ID);

		assertProblem(get(path), "Image %d was not found for claim %d".formatted(UNKNOWN_ID, this.claimId), path);
	}

	@Test
	void imageOfAnotherClaimIsNotFound() {
		var imageId = storeImage(this.claimId, ClaimImageKind.ORIGINAL, "photo.jpg", ClaimImageContentType.JPEG, JPEG_DATA);
		var path = "/api/db/claims/%d/images/%d".formatted(this.otherClaimId, imageId);

		assertProblem(get(path), "Image %d was not found for claim %d".formatted(imageId, this.otherClaimId), path);
	}

	@Test
	void openApiListsBothEndpoints() {
		var paths = get("/q/openapi?format=json").then()
			.statusCode(Status.OK.getStatusCode())
			.extract()
			.jsonPath()
			.getMap("paths");

		assertThat(paths)
			.containsKeys("/api/db/claims/{id}/images", "/api/db/claims/{id}/images/{imageId}");
	}

	private static void assertProblem(Response response, String expectedDetail, String expectedInstance) {
		var problem = response.then()
			.statusCode(Status.NOT_FOUND.getStatusCode())
			.contentType("application/problem+json")
			.extract()
			.jsonPath()
			.getMap(".");

		assertThat(problem)
			.containsExactlyInAnyOrderEntriesOf(Map.of(
				"type", "about:blank",
				"title", "Not Found",
				"status", Status.NOT_FOUND.getStatusCode(),
				"detail", expectedDetail,
				"instance", expectedInstance
			));
	}

	private static long createClaim() {
		return QuarkusTransaction.requiringNew().call(() -> {
			var claim = new Claim();
			claim.category = ClaimCategory.OTHER;
			claim.inceptionDate = LocalDate.of(2020, 1, 1);
			claim.incidentDate = LocalDate.of(2024, 1, 1);
			claim.persist();

			return claim.id;
		});
	}

	private static long storeImage(long claimId, ClaimImageKind kind, String fileName, ClaimImageContentType contentType, byte[] data) {
		return QuarkusTransaction.requiringNew().call(() ->
			ClaimImage.store(Claim.findById(claimId), kind, fileName, contentType, data).id
		);
	}
}