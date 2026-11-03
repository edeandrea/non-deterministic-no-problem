package org.parasol.claim.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimCategory;
import org.parasol.claim.model.ClaimCorrespondence;
import org.parasol.claim.model.ClaimImage;
import org.parasol.claim.model.ClaimImageContentType;
import org.parasol.claim.model.ClaimImageKind;
import org.parasol.claim.model.CorrespondenceDirection;

import io.quarkus.test.junit.QuarkusTest;

/**
 * {@link ClaimMapper}, called directly on hand-built entities: nothing is persisted, so the tests see exactly what the mapping does
 * with its input (the REST tests only see its output). It is a {@code @QuarkusTest} because the mapper is the CDI bean MapStruct
 * generates. MapStruct also fails the build for a DTO field nothing maps to; these tests check the values.
 */
@QuarkusTest
class ClaimMapperTests {
	private static final long CLAIM_ID = 12L;
	private static final byte[] IMAGE_DATA = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3 };
	private static final Instant FIRST = Instant.parse("2026-01-01T10:00:00Z");
	private static final Instant SECOND = Instant.parse("2026-01-01T10:05:00Z");

	@Inject
	ClaimMapper mapper;

	@Test
	void mapsEveryClaimField() {
		var claim = claim(CLAIM_ID);

		claim.claimNumber = "CLM01000000";
		claim.category = ClaimCategory.THEFT;
		claim.policyNumber = "AC-123456789";
		claim.inceptionDate = LocalDate.of(2020, 1, 1);
		claim.clientName = "Jane Doe";
		claim.subject = "Car stolen";
		claim.body = "Somebody took my car.";
		claim.summary = "A stolen car.";
		claim.location = "Elm Street";
		claim.incidentDate = LocalDate.of(2026, 10, 7);
		claim.incidentTime = LocalTime.of(14, 30);
		claim.sentiment = "Upset.";
		claim.emailAddress = "jane.doe@example.com";
		claim.status = "Pending Review";

		assertThat(this.mapper.toDetails(claim))
			.isEqualTo(new ClaimDetails(
				CLAIM_ID,
				"CLM01000000",
				ClaimCategory.THEFT,
				"AC-123456789",
				LocalDate.of(2020, 1, 1),
				"Jane Doe",
				"Car stolen",
				"Somebody took my car.",
				"A stolen car.",
				"Elm Street",
				LocalDate.of(2026, 10, 7),
				LocalTime.of(14, 30),
				"Upset.",
				"jane.doe@example.com",
				"Pending Review"));
	}

	@Test
	void mapsAListOfClaimsInOrder() {
		assertThat(this.mapper.toDetails(List.of(claim(2L), claim(1L))))
			.extracting(ClaimDetails::id)
			.containsExactly(2L, 1L);
	}

	@Test
	void mapsNoClaimsToAnEmptyList() {
		assertThat(this.mapper.toDetails(List.of()))
			.isEmpty();
	}

	@Test
	void leavesOptionalClaimFieldsNull() {
		assertThat(this.mapper.toDetails(claim(CLAIM_ID)))
			.returns(null, ClaimDetails::incidentTime)
			.returns(null, ClaimDetails::summary)
			.returns(null, ClaimDetails::location);
	}

	@Test
	void mapsAnImageToItsMetadataWithAMediaTypeAndARootRelativeUrl() {
		assertThat(this.mapper.toMetadata(image(34L, "damage.png", ClaimImageKind.PROCESSED, ClaimImageContentType.PNG, null), CLAIM_ID))
			.isEqualTo(new ClaimImageMetadata(34L, ClaimImageKind.PROCESSED, "damage.png", "image/png", "/api/db/claims/12/images/34"));
	}

	@Test
	void mapsImagesInOrder() {
		var first = image(2L, "a.jpg", ClaimImageKind.ORIGINAL, ClaimImageContentType.JPEG, null);
		var second = image(1L, "b.png", ClaimImageKind.ORIGINAL, ClaimImageContentType.PNG, null);

		assertThat(this.mapper.toMetadata(List.of(first, second), CLAIM_ID))
			.extracting(ClaimImageMetadata::id)
			.containsExactly(2L, 1L);
	}

	@Test
	void buildsRootRelativeImageUrls() {
		assertThat(this.mapper.imageUrl(12, 34))
			.isEqualTo("/api/db/claims/12/images/34");
	}

	@Test
	void mapsAClaimWithoutEmailsToNoMessages() {
		assertThat(this.mapper.toMessages(List.of(), List.of(), CLAIM_ID))
			.isEmpty();
	}

	@Test
	void mapsEveryMessageField() {
		var email = inbound(5L, FIRST);

		email.createdClaim = true;

		assertThat(this.mapper.toMessages(List.of(email), List.of(), CLAIM_ID))
			.containsExactly(new CorrespondenceMessage(5L, CorrespondenceDirection.INBOUND, "jane.doe@example.com", "Car stolen", FIRST, "Somebody took my car.", true, List.of()));
	}

	// The date is receivedAt, which a sender can't set, not the Date header
	@Test
	void usesTheReceivedDateNotTheSentDate() {
		var email = inbound(5L, FIRST);

		email.sentDate = SECOND;

		assertThat(this.mapper.toMessages(List.of(email), List.of(), CLAIM_ID))
			.extracting(CorrespondenceMessage::date)
			.containsExactly(FIRST);
	}

	@Test
	void mapsAReplyAsOutboundWithoutCreatingTheClaim() {
		var reply = new ClaimCorrespondence();

		reply.id = 6L;
		reply.direction = CorrespondenceDirection.OUTBOUND;
		reply.receivedAt = SECOND;
		reply.sender = "claims@parasol.com";
		reply.subject = "[CLM] Re: Car stolen";
		reply.strippedText = "We got your claim.";

		assertThat(this.mapper.toMessages(List.of(reply), List.of(), CLAIM_ID))
			.containsExactly(new CorrespondenceMessage(6L, CorrespondenceDirection.OUTBOUND, "claims@parasol.com", "[CLM] Re: Car stolen", SECOND, "We got your claim.", false, List.of()));
	}

	@Test
	void keepsTheEmailsInTheOrderGiven() {
		assertThat(this.mapper.toMessages(List.of(inbound(2L, SECOND), inbound(1L, FIRST)), List.of(), CLAIM_ID))
			.extracting(CorrespondenceMessage::id)
			.containsExactly(2L, 1L);
	}

	@Test
	void putsEachImageUnderTheEmailThatCarriedIt() {
		var first = inbound(1L, FIRST);
		var second = inbound(2L, SECOND);
		var images = List.of(
			image(10L, "a.jpg", ClaimImageKind.ORIGINAL, ClaimImageContentType.JPEG, first),
			image(11L, "b.jpg", ClaimImageKind.ORIGINAL, ClaimImageContentType.JPEG, second),
			image(12L, "c.png", ClaimImageKind.ORIGINAL, ClaimImageContentType.PNG, second));

		var messages = this.mapper.toMessages(List.of(first, second), images, CLAIM_ID);

		assertThat(messages.get(0).images())
			.containsExactly(new ClaimImageMetadata(10L, ClaimImageKind.ORIGINAL, "a.jpg", "image/jpeg", "/api/db/claims/12/images/10"));
		assertThat(messages.get(1).images())
			.containsExactly(
				new ClaimImageMetadata(11L, ClaimImageKind.ORIGINAL, "b.jpg", "image/jpeg", "/api/db/claims/12/images/11"),
				new ClaimImageMetadata(12L, ClaimImageKind.ORIGINAL, "c.png", "image/png", "/api/db/claims/12/images/12"));
	}

	@Test
	void anEmailWithoutPhotosGetsNoImages() {
		var first = inbound(1L, FIRST);
		var second = inbound(2L, SECOND);
		var images = List.of(image(10L, "a.jpg", ClaimImageKind.ORIGINAL, ClaimImageContentType.JPEG, first));

		assertThat(this.mapper.toMessages(List.of(first, second), images, CLAIM_ID).get(1).images())
			.isEmpty();
	}

	// A seeded image (or one stored before emails were linked to them) belongs to no email, so it appears in no message
	@Test
	void leavesAnImageNoEmailCarriedOutOfEveryMessage() {
		var seeded = image(10L, "seeded.jpg", ClaimImageKind.ORIGINAL, ClaimImageContentType.JPEG, null);

		assertThat(this.mapper.toMessages(List.of(inbound(1L, FIRST)), List.of(seeded), CLAIM_ID))
			.singleElement()
			.satisfies(message -> assertThat(message.images()).isEmpty());
	}

	@Test
	void doesNotPutAnImageOfAnEmailNotInTheListUnderAMessage() {
		var other = image(10L, "other.jpg", ClaimImageKind.ORIGINAL, ClaimImageContentType.JPEG, inbound(99L, FIRST));

		assertThat(this.mapper.toMessages(List.of(inbound(1L, FIRST)), List.of(other), CLAIM_ID))
			.singleElement()
			.satisfies(message -> assertThat(message.images()).isEmpty());
	}

	private static Claim claim(long id) {
		var claim = new Claim();

		claim.id = id;

		return claim;
	}

	private static ClaimCorrespondence inbound(long id, Instant receivedAt) {
		var email = new ClaimCorrespondence();

		email.id = id;
		email.messageId = "<in-%d@example.com>".formatted(id);
		email.direction = CorrespondenceDirection.INBOUND;
		email.sentDate = receivedAt;
		email.receivedAt = receivedAt;
		email.sender = "jane.doe@example.com";
		email.subject = "Car stolen";
		email.strippedText = "Somebody took my car.";

		return email;
	}

	private static ClaimImage image(long id, String fileName, ClaimImageKind kind, ClaimImageContentType contentType, ClaimCorrespondence correspondence) {
		var image = new ClaimImage();

		image.id = id;
		image.fileName = fileName;
		image.kind = kind;
		image.contentType = contentType;
		image.data = IMAGE_DATA;
		image.correspondence = correspondence;

		return image;
	}
}
