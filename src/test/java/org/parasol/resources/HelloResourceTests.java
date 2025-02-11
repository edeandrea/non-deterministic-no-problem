package org.parasol.resources;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.parasol.resources.HelloResource.HelloObject;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;

@QuarkusTest
@TestHTTPEndpoint(HelloResource.class)
class HelloResourceTests {
	@TestHTTPResource
	@TestHTTPEndpoint(HelloResource.class)
	URL url;

	@BeforeAll
	static void beforeAll() {
		RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
	}

	@Test
	void helloWorld() {
		System.out.println("url = " + url);
		var helloObject = given()
			.log().all(true)
			.when().get("/").then()
			.log().all(true)
      .statusCode(200)
			.contentType(ContentType.JSON)
			.extract().as(HelloObject.class);

		assertThat(helloObject)
			.isNotNull()
			.extracting(
				HelloObject::message,
				HelloObject::description
			)
			.containsExactly(
				"Hello World",
				"This is a hello world message"
			);
	}
}