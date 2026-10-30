package org.parasol.claim.rest;

import static io.restassured.RestAssured.get;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.ws.rs.core.Response.Status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimCategory;
import org.parasol.claim.model.ClaimCorrespondence;
import org.parasol.claim.model.ClaimImage;
import org.parasol.claim.model.ClaimImageContentType;
import org.parasol.claim.model.ClaimImageKind;
import org.parasol.claim.model.CorrespondenceDirection;
import org.parasol.problem.ProblemDetail;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

import io.restassured.http.ContentType;

// Not @TestTransaction, for the reason ClaimImageResourceTests gives: the endpoint runs over HTTP in its own transaction, so
// the fixtures are committed with QuarkusTransaction.requiringNew() and deleted afterwards (ClaimsListPageTests expects exactly
// the 6 seeded claims). Deleting a claim deletes its emails and images.
// Responses are compared as CorrespondenceMessage records; jsonFieldNames pins the wire format, which a record round-trip can't see.
@QuarkusTest
class ClaimCorrespondenceResourceTests {
	private static final byte[] JPEG_DATA = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 1, 2 };
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
	void listsTheEmailsInOrderWithTheirOwnImages() {
		var inbound = QuarkusTransaction.requiringNew().call(() -> {
			var claim = Claim.<Claim>findById(this.claimId);
			var row = inbound(claim, "<in-1@example.com>", "Car accident", "I hit a post.", "2026-01-01T10:00:00Z");
			row.createdClaim = true;
			var image = ClaimImage.store(row, ClaimImageKind.ORIGINAL, "front.jpg", ClaimImageContentType.JPEG, JPEG_DATA);

			return new Stored(row.id, image.id);
		});
		var outbound = storeReply(this.claimId, "<out-1@parasol.com>", "[CLM] Re: Car accident", "We got your claim.");
		// An image that no email carried (a seeded one) is not part of any message
		storeUnlinkedImage(this.claimId);

		assertThat(listCorrespondence(this.claimId))
			.containsExactly(
				new CorrespondenceMessage(
					inbound.messageId(),
					CorrespondenceDirection.INBOUND,
					"jane.doe@example.com",
					"Car accident",
					Instant.parse("2026-01-01T10:00:00Z"),
					"I hit a post.",
					true,
					List.of(new ClaimImageMetadata(inbound.imageId(), ClaimImageKind.ORIGINAL, "front.jpg", "image/jpeg", "/api/db/claims/%d/images/%d".formatted(this.claimId, inbound.imageId())))),
				new CorrespondenceMessage(outbound.id(), CorrespondenceDirection.OUTBOUND, "claims@parasol.com", "[CLM] Re: Car accident", outbound.date(), "We got your claim.", false, List.of()));
	}

	// The mail server's clock dates the customer's email, ours dates the reply, and the two can disagree (separate pods on
	// OpenShift). An answer the mail server dated before the reply it answers is still listed after it
	@Test
	void listsTheEmailsInTheOrderTheyWereRecordedWhateverTheirDates() {
		var reply = storeReply(this.claimId, "<out-order@parasol.com>", "[CLM] Re: Car accident", "Where did it happen?");
		var answerId = QuarkusTransaction.requiringNew().call(() ->
			inbound(Claim.<Claim>findById(this.claimId), "<in-order@example.com>", "Re: [CLM] Re: Car accident", "In the car park.", "2026-01-01T10:00:00Z").id);

		assertThat(listCorrespondence(this.claimId))
			.extracting(CorrespondenceMessage::id)
			.containsExactly(reply.id(), answerId);
	}

	@Test
	void doesNotIncludeAnotherClaimsEmails() {
		storeReply(this.otherClaimId, "<out-other@parasol.com>", "Other", "Other claim");

		assertThat(listCorrespondence(this.claimId))
			.isEmpty();
	}

	@Test
	void listsNothingForAClaimWithoutEmails() {
		assertThat(listCorrespondence(this.claimId))
			.isEmpty();
	}

	@Test
	void jsonFieldNames() {
		QuarkusTransaction.requiringNew().run(() -> {
			var row = ClaimCorrespondence.recordReply(Claim.<Claim>findById(this.claimId), "<out-json@parasol.com>", "claims@parasol.com", "Subject", "Text");

			ClaimImage.store(row, ClaimImageKind.ORIGINAL, "photo.jpg", ClaimImageContentType.JPEG, JPEG_DATA);
		});

		var json = get("/api/db/claims/{id}/correspondence", this.claimId).then()
			.statusCode(Status.OK.getStatusCode())
			.contentType(ContentType.JSON)
			.extract()
			.jsonPath();

		// Deliberately raw JSON: only the keys show the wire format the UI reads (see ClaimImageResourceTests)
		assertThat(json.getMap("[0]").keySet())
			.containsExactlyInAnyOrder("id", "direction", "sender", "subject", "date", "text", "created_claim", "images");
	}

	// The global non-empty inclusion leaves out an empty list, so the UI must read a missing images as none (Correspondence.tsx)
	@Test
	void anEmailWithoutPhotosHasNoImagesKey() {
		storeReply(this.claimId, "<out-no-images@parasol.com>", "Subject", "Text");

		assertThat(get("/api/db/claims/{id}/correspondence", this.claimId).then().statusCode(Status.OK.getStatusCode()).extract().jsonPath().getMap("[0]").keySet())
			.containsExactlyInAnyOrder("id", "direction", "sender", "subject", "date", "text", "created_claim");
	}

	@Test
	void anUnknownClaimIsNotFound() {
		var path = "/api/db/claims/%d/correspondence".formatted(UNKNOWN_ID);
		var problem = get(path).then()
			.statusCode(Status.NOT_FOUND.getStatusCode())
			.contentType(ProblemDetail.MEDIA_TYPE)
			.extract()
			.as(ProblemDetail.class);

		assertThat(problem)
			.isEqualTo(new ProblemDetail("about:blank", "Not Found", Status.NOT_FOUND.getStatusCode(), "Claim %d was not found".formatted(UNKNOWN_ID), path));
	}

	private static List<CorrespondenceMessage> listCorrespondence(long claimId) {
		return get("/api/db/claims/{id}/correspondence", claimId).then()
			.statusCode(Status.OK.getStatusCode())
			.contentType(ContentType.JSON)
			.extract()
			.jsonPath()
			.getList(".", CorrespondenceMessage.class);
	}

	private static ClaimCorrespondence inbound(Claim claim, String messageId, String subject, String text, String receivedAt) {
		var row = new ClaimCorrespondence();

		row.messageId = messageId;
		row.claim = claim;
		row.direction = CorrespondenceDirection.INBOUND;
		row.sentDate = Instant.parse(receivedAt);
		row.receivedAt = Instant.parse(receivedAt);
		row.sender = "jane.doe@example.com";
		row.subject = subject;
		row.strippedText = text;
		row.persist();

		return row;
	}

	// Recorded after any inbound row the test stored first, so it's listed after it
	private static Reply storeReply(long claimId, String messageId, String subject, String text) {
		return QuarkusTransaction.requiringNew().call(() -> {
			var row = ClaimCorrespondence.recordReply(Claim.<Claim>findById(claimId), messageId, "claims@parasol.com", subject, text);

			return new Reply(row.id, row.receivedAt);
		});
	}

	private static void storeUnlinkedImage(long claimId) {
		QuarkusTransaction.requiringNew().run(() ->
			ClaimImage.store(Claim.<Claim>findById(claimId), ClaimImageKind.ORIGINAL, "seeded.jpg", ClaimImageContentType.JPEG, JPEG_DATA));
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

	private record Stored(long messageId, long imageId) {
	}

	private record Reply(long id, Instant date) {
	}
}
