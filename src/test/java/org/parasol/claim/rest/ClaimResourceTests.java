package org.parasol.claim.rest;

import static io.restassured.RestAssured.get;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import jakarta.ws.rs.core.Response.Status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimCategory;
import org.parasol.claim.rest.ClaimExceptionMappings.ProblemDetail;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.junit.QuarkusTest;

import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;

// Most tests mock Claim's static Panache methods, so they never touch the database; missingClaimIsNotFoundAgainstTheDatabase
// runs the real lookup. Responses are compared as ClaimDetails / ProblemDetail records. The JSON-level tests
// (jsonFieldNames, categoryIsSerializedAsLabel, nullIncidentTimeIsOmitted) pin the wire format the UI reads, which a
// record round-trip can't see: the same @JsonNaming drives both serialization and deserialization.
@QuarkusTest
class ClaimResourceTests {
	private static final long CLAIM_ID = 42L;

	private static final ClaimDetails EXPECTED = new ClaimDetails(
		CLAIM_ID,
		"CLM01000000",
		ClaimCategory.OTHER,
		"123",
		LocalDate.of(1954, 9, 30),
		"client",
		"collision",
		"body",
		"Car was damaged in accident",
		"driveway",
		LocalDate.of(1955, 1, 2),
		LocalTime.of(15, 30),
		"Very bad",
		"client@example.com",
		"New"
	);

	@Test
	void getAllNoneFound() {
		PanacheMock.mock(Claim.class);
		when(Claim.listAll())
			.thenReturn(List.of());

		assertThat(getClaims())
			.isEmpty();

		PanacheMock.verify(Claim.class).listAll();
		PanacheMock.verifyNoMoreInteractions(Claim.class);
	}

	@Test
	void getAllSomeFound() {
		PanacheMock.mock(Claim.class);
		when(Claim.listAll())
			.thenReturn(List.of(createClaim()));

		assertThat(getClaims())
			.containsExactly(EXPECTED);

		PanacheMock.verify(Claim.class).listAll();
		PanacheMock.verifyNoMoreInteractions(Claim.class);
	}

	@Test
	void getOneFound() {
		PanacheMock.mock(Claim.class);
		when(Claim.findByIdOptional(CLAIM_ID))
			.thenReturn(Optional.of(createClaim()));

		assertThat(getClaim())
			.isEqualTo(EXPECTED);

		PanacheMock.verify(Claim.class).findByIdOptional(CLAIM_ID);
		PanacheMock.verifyNoMoreInteractions(Claim.class);
	}

	@Test
	void jsonFieldNames() {
		PanacheMock.mock(Claim.class);
		when(Claim.findByIdOptional(CLAIM_ID))
			.thenReturn(Optional.of(createClaim()));

		// Deliberately raw JSON, not as(ClaimDetails.class): the record serializes and deserializes through the
		// same @JsonNaming, so a round-trip passes even if the keys change (e.g. clientName instead of client_name) and the
		// UI breaks. Only the raw keys show the wire format. Exactly these snake_case keys: the UI reads them, and an entity
		// column not in ClaimDetails can't leak in. The typed comparison is in getOneFound / getAllSomeFound.
		assertThat(getJson("/api/db/claims/{id}", CLAIM_ID).getMap(".").keySet())
			.containsExactlyInAnyOrder(
				"id",
				"claim_number",
				"category",
				"policy_number",
				"inception_date",
				"client_name",
				"subject",
				"body",
				"summary",
				"location",
				"incident_date",
				"incident_time",
				"sentiment",
				"email_address",
				"status"
			);
	}

	@Test
	void getOneNotFoundIsProblemDetails() {
		PanacheMock.mock(Claim.class);
		when(Claim.findByIdOptional(CLAIM_ID))
			.thenReturn(Optional.empty());

		assertThat(getProblem("/api/db/claims/{id}", CLAIM_ID))
			.isEqualTo(notFound("Claim %d was not found".formatted(CLAIM_ID), "/api/db/claims/%d".formatted(CLAIM_ID)));
	}

	@Test
	void missingClaimIsNotFoundAgainstTheDatabase() {
		assertThat(getProblem("/api/db/claims/{id}", -1L))
			.isEqualTo(notFound("Claim -1 was not found", "/api/db/claims/-1"));
	}

	@ParameterizedTest
	@EnumSource(ClaimCategory.class)
	void categoryIsSerializedAsLabel(ClaimCategory category) {
		var claim = createClaim();
		claim.category = category;

		PanacheMock.mock(Claim.class);
		when(Claim.findByIdOptional(CLAIM_ID))
			.thenReturn(Optional.of(claim));

		assertThat(getJson("/api/db/claims/{id}", CLAIM_ID).getString("category"))
			.isEqualTo(category.label());
	}

	@Test
	void nullIncidentTimeIsOmitted() {
		var claim = createClaim();
		claim.incidentTime = null;

		PanacheMock.mock(Claim.class);
		when(Claim.findByIdOptional(CLAIM_ID))
			.thenReturn(Optional.of(claim));

		assertThat(getJson("/api/db/claims/{id}", CLAIM_ID).getMap("."))
			.containsEntry("incident_date", "1955-01-02")
			.doesNotContainKey("incident_time");
	}

	private static List<ClaimDetails> getClaims() {
		return get("/api/db/claims").then()
			.statusCode(Status.OK.getStatusCode())
			.contentType(ContentType.JSON)
			.extract()
			.jsonPath()
			.getList(".", ClaimDetails.class);
	}

	private static ClaimDetails getClaim() {
		return get("/api/db/claims/{id}", CLAIM_ID).then()
			.statusCode(Status.OK.getStatusCode())
			.contentType(ContentType.JSON)
			.extract()
			.as(ClaimDetails.class);
	}

	private static JsonPath getJson(String path, Object... pathParams) {
		return get(path, pathParams).then()
			.statusCode(Status.OK.getStatusCode())
			.contentType(ContentType.JSON)
			.extract()
			.jsonPath();
	}

	private static ProblemDetail getProblem(String path, Object... pathParams) {
		return get(path, pathParams).then()
			.statusCode(Status.NOT_FOUND.getStatusCode())
			.contentType(ClaimExceptionMappings.PROBLEM_JSON)
			.extract()
			.as(ProblemDetail.class);
	}

	private static ProblemDetail notFound(String detail, String instance) {
		return new ProblemDetail("about:blank", "Not Found", Status.NOT_FOUND.getStatusCode(), detail, instance);
	}

	private static Claim createClaim() {
		var claim = new Claim();
		claim.id = CLAIM_ID;
		claim.claimNumber = "CLM01000000";
		claim.category = ClaimCategory.OTHER;
		claim.policyNumber = "123";
		claim.inceptionDate = LocalDate.of(1954, 9, 30);
		claim.clientName = "client";
		claim.subject = "collision";
		claim.body = "body";
		claim.summary = "Car was damaged in accident";
		claim.location = "driveway";
		claim.incidentDate = LocalDate.of(1955, 1, 2);
		claim.incidentTime = LocalTime.of(15, 30);
		claim.sentiment = "Very bad";
		claim.emailAddress = "client@example.com";
		claim.status = "New";

		return claim;
	}
}