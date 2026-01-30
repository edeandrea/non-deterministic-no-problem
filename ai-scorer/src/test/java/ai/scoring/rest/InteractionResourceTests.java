package ai.scoring.rest;

import static io.restassured.RestAssured.get;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.ws.rs.core.Response.Status;

import org.junit.jupiter.api.Test;

import ai.scoring.domain.interaction.Interaction;
import ai.scoring.domain.interaction.InteractionMode;
import ai.scoring.domain.interaction.InteractionQuery;
import ai.scoring.domain.interaction.InteractionScore;
import ai.scoring.mapping.InteractionMapperTests;
import ai.scoring.service.InteractionService;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

import io.restassured.http.ContentType;

@QuarkusTest
class InteractionResourceTests {
	@InjectMock
	InteractionService interactionService;

	@Test
	void findInteractionsNothingFound() {
		when(this.interactionService.findInteractions(any(InteractionQuery.class)))
			.thenReturn(List.of());

		get("/ai/interactions").then()
			.statusCode(Status.NOT_FOUND.getStatusCode());
	}

	@Test
	void findByUUIDNotFound() {
		when(this.interactionService.getInteraction(any(UUID.class)))
			.thenReturn(Optional.empty());

		get("/ai/interactions/{uuid}", UUID.randomUUID()).then()
			.statusCode(Status.NOT_FOUND.getStatusCode());
	}

	@Test
	void findByUUIDFound() {
		var interaction = Interaction.builder()
		                             .applicationName("app")
		                             .interactionId(UUID.randomUUID())
		                             .interactionDate(Instant.now())
		                             .interfaceName("iface")
		                             .methodName("method")
		                             .systemMessage("System message")
		                             .userMessage("User message")
		                             .result("Result")
		                             .build();

		var score = InteractionScore.builder()
			                .score(0.5)
			                .scoreDate(Instant.now())
			                .mode(InteractionMode.NORMAL)
			                .interaction(interaction)
			                .build();

		interaction.getScores().add(score);

		when(this.interactionService.getInteraction(interaction.getInteractionId()))
			.thenReturn(Optional.of(interaction));

		var response = get("/ai/interactions/{uuid}", interaction.getInteractionId().toString()).then()
			.statusCode(Status.OK.getStatusCode())
			.contentType(ContentType.JSON)
			.extract().as(ai.scoring.model.Interaction.class);

		InteractionMapperTests.assertMappingCorrect(response, interaction);
	}
}