package org.parasol.claim.rest;

import static io.restassured.RestAssured.get;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import jakarta.ws.rs.core.Response.Status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimCategory;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.junit.QuarkusTest;

import io.restassured.http.ContentType;

@QuarkusTest
class ClaimResourceTests {
	@Test
	void getAllNoneFound() {
		PanacheMock.mock(Claim.class);

		when(Claim.listAll())
			.thenReturn(List.of());

		get("/api/db/claims").then()
			.statusCode(Status.OK.getStatusCode())
			.contentType(ContentType.JSON)
			.body("$.size()", is(0));

		PanacheMock.verify(Claim.class).listAll();
		PanacheMock.verifyNoMoreInteractions(Claim.class);
	}

	@Test
	void getAllSomeFound() {
		PanacheMock.mock(Claim.class);
		when(Claim.listAll())
			.thenReturn(List.of(createClaim()));

		var claims = get("/api/db/claims").then()
			.statusCode(Status.OK.getStatusCode())
			.contentType(ContentType.JSON)
			.extract().body()
			.jsonPath().getList(".", Claim.class);

		assertThat(claims)
			.isNotNull()
			.singleElement()
			.usingRecursiveComparison()
			.isEqualTo(createClaim());

		PanacheMock.verify(Claim.class).listAll();
		PanacheMock.verifyNoMoreInteractions(Claim.class);
	}

	@Test
	void getOneNotFound() {
		PanacheMock.mock(Claim.class);
		when(Claim.findById(1))
			.thenReturn(null);

		get("/api/db/claims/{id}", 1).then()
			.statusCode(Status.NO_CONTENT.getStatusCode())
			.contentType(ContentType.JSON)
			.body(blankOrNullString());

		PanacheMock.verify(Claim.class).findById(1);
		PanacheMock.verifyNoMoreInteractions(Claim.class);
	}

	@Test
	void getOneFound() {
		PanacheMock.mock(Claim.class);
		when(Claim.findById(1))
			.thenReturn(createClaim());

		var claim = get("/api/db/claims/{id}", 1).then()
			.statusCode(Status.OK.getStatusCode())
			.contentType(ContentType.JSON)
			.extract().as(Claim.class);

		assertThat(claim)
			.isNotNull()
			.usingRecursiveComparison()
			.isEqualTo(createClaim());

		PanacheMock.verify(Claim.class);
		Claim.findById(1);
		PanacheMock.verifyNoMoreInteractions(Claim.class);
	}

	@ParameterizedTest
	@EnumSource(ClaimCategory.class)
	void categoryIsSerializedAsLabel(ClaimCategory category) {
		var claim = createClaim();
		claim.category = category;

		PanacheMock.mock(Claim.class);
		when(Claim.findById(1))
			.thenReturn(claim);

		var json = get("/api/db/claims/{id}", 1).then()
			.statusCode(Status.OK.getStatusCode())
			.contentType(ContentType.JSON)
			.extract().jsonPath();

		assertThat(json.getString("category"))
			.isEqualTo(category.label());

		assertThat(json.getObject(".", Claim.class))
			.extracting(c -> c.category)
			.isEqualTo(category);
	}

	@Test
	void incidentDateAndTimeAreSerialized() {
		PanacheMock.mock(Claim.class);
		when(Claim.findById(1))
			.thenReturn(createClaim());

		var json = get("/api/db/claims/{id}", 1).then()
			.statusCode(Status.OK.getStatusCode())
			.contentType(ContentType.JSON)
			.extract().jsonPath();

		assertThat(json.getMap("."))
			.containsEntry("incident_date", "1955-01-02")
			.containsEntry("incident_time", "15:30:00")
			.doesNotContainKeys("time", "claim_time");
	}

	@Test
	void nullIncidentTimeIsOmitted() {
		var claim = createClaim();
		claim.incidentTime = null;

		PanacheMock.mock(Claim.class);
		when(Claim.findById(1))
			.thenReturn(claim);

		var json = get("/api/db/claims/{id}", 1).then()
			.statusCode(Status.OK.getStatusCode())
			.contentType(ContentType.JSON)
			.extract().jsonPath();

		assertThat(json.getMap("."))
			.containsEntry("incident_date", "1955-01-02")
			.doesNotContainKey("incident_time");
	}

	private static Claim createClaim() {
		var claim = new Claim();
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

		return claim;
	}
}