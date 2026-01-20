package org.parasol.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.parasol.model.audit.InputGuardrailExecutedAuditEvent;
import org.parasol.model.audit.Interactions.Interaction;
import org.parasol.model.audit.InvocationContext;
import org.parasol.model.audit.OutputGuardrailExecutedAuditEvent;
import org.parasol.model.audit.ServiceCompleteAuditEvent;
import org.parasol.model.audit.ServiceErrorAuditEvent;
import org.parasol.model.audit.ServiceStartedAuditEvent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrail;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@TestMethodOrder(OrderAnnotation.class)
class AuditEventRepositoryTests {
  private static final String EVENT_QUERY_TEMPLATE = "from AuditEvent e WHERE type(e) = %s AND invocationContext.interactionId = ?1";

  @Inject
  AuditEventRepository repository;

  @Inject
  ObjectMapper objectMapper;

  private record SomeObject(String field1, int field2) {
  }

  @Test
  @TestTransaction
  @Order(0)
  void interactions() throws JsonProcessingException {
    // Set up some interactions
	  this.repository.deleteAll();
    var first = serviceStarted();
    var firstComplete = interactionComplete(first);
    var second = serviceStarted();
    var secondFailed = interactionFailed(second);
    this.repository.flush();

    // Get the interactions
    var interactions = this.repository.getLLMInteractions(Optional.empty(), Optional.empty());

    assertThat(interactions).isNotNull();

    assertThat(interactions.interactions())
      .isNotNull()
      .hasSize(2)
      .containsExactlyInAnyOrder(
            Interaction.success(
							first.getInvocationContext().getInteractionId(),
              first.getCreatedOn(),
              first.getSystemMessage(),
              first.getUserMessage(),
              firstComplete.getResult()),
            Interaction.failure(
							second.getInvocationContext().getInteractionId(),
              second.getCreatedOn(),
              second.getSystemMessage(),
              second.getUserMessage(),
              secondFailed.getErrorMessage(),
              secondFailed.getCauseErrorMessage()));
  }

  @Test
  @TestTransaction
  @Order(1)
  void serviceStartedCreated() {
    serviceStarted();
  }

  @Test
  @TestTransaction
  @Order(1)
  void interactionComplete() throws JsonProcessingException {
    interactionComplete(serviceStarted());
  }

  @Test
  @TestTransaction
  @Order(1)
  void interactionFailed() {
    interactionFailed(serviceStarted());
  }

	@Test
	@TestTransaction
	@Order(2)
	void inputGuardrail() {
		inputGuardrailExecuted();
	}

	@Test
	@TestTransaction
	@Order(2)
	void outputGuardrail() {
		outputGuardrailExecuted();
	}

  private ServiceErrorAuditEvent interactionFailed(ServiceStartedAuditEvent serviceStartedAuditEvent) {
    var interactionFailedEvent = ServiceErrorAuditEvent.builder()
                                                       .errorMessage("Some error message")
                                                       .causeErrorMessage("Some cause error message")
                                                       .invocationContext(serviceStartedAuditEvent.getInvocationContext())
                                                       .build();

    this.repository.persistAndFlush(interactionFailedEvent);
    var interactionFailedEvents = this.repository.find(
						EVENT_QUERY_TEMPLATE.formatted(ServiceErrorAuditEvent.class.getSimpleName()),
            interactionFailedEvent.getInvocationContext().getInteractionId()
          )
         .list();

    assertThat(interactionFailedEvents)
      .singleElement()
      .usingRecursiveComparison()
      .isEqualTo(interactionFailedEvent);

    return interactionFailedEvent;
  }

  private ServiceCompleteAuditEvent interactionComplete(ServiceStartedAuditEvent serviceStartedAuditEvent) throws JsonProcessingException {
    var someObj = new SomeObject("field1", 1);
    var interactionCompleteEvent = ServiceCompleteAuditEvent.builder()
                                                            .result(this.objectMapper.writeValueAsString(someObj))
                                                            .invocationContext(serviceStartedAuditEvent.getInvocationContext())
                                                            .build();

    this.repository.persistAndFlush(interactionCompleteEvent);
    var interactionCompleteEvents = this.repository.find(
							EVENT_QUERY_TEMPLATE.formatted(ServiceCompleteAuditEvent.class.getSimpleName()),
              interactionCompleteEvent.getInvocationContext().getInteractionId()
            )
            .list();

    assertThat(interactionCompleteEvents)
      .singleElement()
      .usingRecursiveComparison()
      .isEqualTo(interactionCompleteEvent);

    assertThat(interactionCompleteEvents.getFirst())
	    .isNotNull()
	    .isInstanceOf(ServiceCompleteAuditEvent.class).extracting(e -> {
        try {
	        return this.objectMapper.readValue(((ServiceCompleteAuditEvent) e).getResult(), SomeObject.class);
        } catch (JsonProcessingException ex) {
	        throw new RuntimeException(ex);
        }
      })
	    .usingRecursiveComparison()
	    .isEqualTo(someObj);

    return interactionCompleteEvent;
  }

	private InputGuardrailExecutedAuditEvent inputGuardrailExecuted() {
		var inputGuardrailExecutedAuditEvent = InputGuardrailExecutedAuditEvent.builder()
			.duration(Duration.ofSeconds(3))
			.guardrailClass(IG.class.getName())
			.invocationContext(InvocationContext.builder()
							            .interactionId(UUID.randomUUID())
							            .interfaceName("someInterface")
							            .methodName("someMethod")
							            .build()
             )
			.result("result")
			.userMessage("user message")
			.build();

		this.repository.persistAndFlush(inputGuardrailExecutedAuditEvent);

		var igEvents = this.repository.find(
			EVENT_QUERY_TEMPLATE.formatted(InputGuardrailExecutedAuditEvent.class.getSimpleName()),
			inputGuardrailExecutedAuditEvent.getInvocationContext().getInteractionId()
		)
			.list();

		assertThat(igEvents)
			.singleElement()
			.usingRecursiveComparison()
			.isEqualTo(inputGuardrailExecutedAuditEvent);

		return inputGuardrailExecutedAuditEvent;
	}

	private OutputGuardrailExecutedAuditEvent outputGuardrailExecuted() {
		var outputGuardrailExecutedAuditEvent = OutputGuardrailExecutedAuditEvent.builder()
			.duration(Duration.ofSeconds(3))
			.guardrailClass(OG.class.getName())
			.invocationContext(InvocationContext.builder()
							            .interactionId(UUID.randomUUID())
							            .interfaceName("someInterface")
							            .methodName("someMethod")
							            .build()
             )
			.response("response")
			.result("result")
			.build();

		this.repository.persistAndFlush(outputGuardrailExecutedAuditEvent);

		var ogEvents = this.repository.find(
			EVENT_QUERY_TEMPLATE.formatted(OutputGuardrailExecutedAuditEvent.class.getSimpleName()),
			outputGuardrailExecutedAuditEvent.getInvocationContext().getInteractionId()
		)
			.list();

		assertThat(ogEvents)
			.singleElement()
			.usingRecursiveComparison()
			.isEqualTo(outputGuardrailExecutedAuditEvent);

		return outputGuardrailExecutedAuditEvent;
	}

  private ServiceStartedAuditEvent serviceStarted() {
    var serviceStartedAuditEvent = ServiceStartedAuditEvent.builder()
                                                              .systemMessage("System message")
                                                              .userMessage("User message")
                                                              .invocationContext(
							 InvocationContext.builder()
							            .interactionId(UUID.randomUUID())
							            .interfaceName("someInterface")
							            .methodName("someMethod")
							            .build()
             )
                                                              .build();

    this.repository.persistAndFlush(serviceStartedAuditEvent);
    var serviceStartedEvents = this.repository.find(
			              EVENT_QUERY_TEMPLATE.formatted(ServiceStartedAuditEvent.class.getSimpleName()),
                    serviceStartedAuditEvent.getInvocationContext().getInteractionId()
             )
            .list();

    assertThat(serviceStartedEvents)
      .singleElement()
      .usingRecursiveComparison()
      .isEqualTo(serviceStartedAuditEvent);

    return serviceStartedAuditEvent;
  }

	private static class IG implements InputGuardrail {

	}

	private static class OG implements OutputGuardrail {
	}
}