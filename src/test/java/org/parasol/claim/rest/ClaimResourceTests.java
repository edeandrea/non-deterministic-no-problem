package org.parasol.claim.rest;

import static io.restassured.RestAssured.get;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import jakarta.ws.rs.core.Response.Status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimCategory;
import org.parasol.claim.model.ClaimNotFoundException;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.junit.QuarkusTest;

import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;

// Most tests mock Claim's static Panache methods (Claim.findExisting is stubbed directly, including to throw
// ClaimNotFoundException), so they never touch the database. getAllSomeFound and getOneFound pin the exact JSON: an
// entity column that isn't in ClaimDetails can't leak into the API. missingClaimIsNotFoundAgainstTheDatabase runs
// the real lookup.
@QuarkusTest
class ClaimResourceTests {
	private static final long CLAIM_ID = 42L;

	// Every field the API exposes, with the JSON names and formats the UI reads
	private static final Map<String, Object> EXPECTED_JSON = Map.ofEntries(
		Map.entry("id", (int) CLAIM_ID),
		Map.entry("claim_number", "CLM01000000"),
		Map.entry("category", "Other"),
		Map.entry("policy_number", "123"),
		Map.entry("inception_date", "1954-09-30"),
		Map.entry("client_name", "client"),
		Map.entry("subject", "collision"),
		Map.entry("body", "body"),
		Map.entry("summary", "Car was damaged in accident"),
		Map.entry("location", "driveway"),
		Map.entry("incident_date", "1955-01-02"),
		Map.entry("incident_time", "15:30:00"),
		Map.entry("sentiment", "Very bad"),
		Map.entry("email_address", "client@example.com"),
		Map.entry("status", "New")
	);

	@Test
	void getAllNoneFound() {
		PanacheMock.mock(Claim.class);
		when(Claim.listAll())
			.thenReturn(List.of());

		var claims = getJson("/api/db/claims")
			.getList(".");

		assertThat(claims)
			.isEmpty();

		PanacheMock.verify(Claim.class).listAll();
		PanacheMock.verifyNoMoreInteractions(Claim.class);
	}

	@Test
	void getAllSomeFound() {
		PanacheMock.mock(Claim.class);
		when(Claim.listAll())
			.thenReturn(List.of(createClaim()));

		var claims = getJson("/api/db/claims")
			.getList(".", Map.class);

		assertThat(claims)
			.singleElement()
			.isEqualTo(EXPECTED_JSON);

		PanacheMock.verify(Claim.class).listAll();
		PanacheMock.verifyNoMoreInteractions(Claim.class);
	}

	@Test
	void getOneFound() {
		PanacheMock.mock(Claim.class);
		when(Claim.findExisting(CLAIM_ID))
			.thenReturn(createClaim());

		var claim = getJson("/api/db/claims/{id}", CLAIM_ID)
			.getMap(".");

		assertThat(claim)
			.isEqualTo(EXPECTED_JSON);

		PanacheMock.verify(Claim.class).findExisting(CLAIM_ID);
		PanacheMock.verifyNoMoreInteractions(Claim.class);
	}

	@Test
	void getOneNotFoundIsProblemDetails() {
		PanacheMock.mock(Claim.class);
		when(Claim.findExisting(CLAIM_ID))
			.thenThrow(new ClaimNotFoundException(CLAIM_ID));

		var problem = get("/api/db/claims/{id}", CLAIM_ID).then()
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
				"detail", "Claim %d was not found".formatted(CLAIM_ID),
				"instance", "/api/db/claims/%d".formatted(CLAIM_ID)
			));
	}

	@Test
	void missingClaimIsNotFoundAgainstTheDatabase() {
		var problem = get("/api/db/claims/{id}", -1L).then()
			.statusCode(Status.NOT_FOUND.getStatusCode())
			.contentType("application/problem+json")
			.extract()
			.jsonPath();

		assertThat(problem.getString("detail"))
			.isEqualTo("Claim -1 was not found");
	}


	@ParameterizedTest
	@EnumSource(ClaimCategory.class)
	void categoryIsSerializedAsLabel(ClaimCategory category) {
		var claim = createClaim();
		claim.category = category;

		PanacheMock.mock(Claim.class);
		when(Claim.findExisting(CLAIM_ID))
			.thenReturn(claim);

		assertThat(getJson("/api/db/claims/{id}", CLAIM_ID).getString("category"))
			.isEqualTo(category.label());
	}

	@Test
	void nullIncidentTimeIsOmitted() {
		var claim = createClaim();
		claim.incidentTime = null;

		PanacheMock.mock(Claim.class);
		when(Claim.findExisting(CLAIM_ID))
			.thenReturn(claim);

		assertThat(getJson("/api/db/claims/{id}", CLAIM_ID).getMap("."))
			.containsEntry("incident_date", "1955-01-02")
			.doesNotContainKey("incident_time");
	}

	private static JsonPath getJson(String path, Object... pathParams) {
		return get(path, pathParams).then()
			.statusCode(Status.OK.getStatusCode())
			.contentType(ContentType.JSON)
			.extract()
			.jsonPath();
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