package org.parasol.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.parasol.model.audit.Interactions.Interaction;
import org.parasol.model.audit.InvocationContext;
import org.parasol.model.audit.ServiceCompleteAuditEvent;
import org.parasol.model.audit.ServiceErrorAuditEvent;
import org.parasol.model.audit.ServiceStartedAuditEvent;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    var first = initialMessages();
    var firstComplete = interactionComplete(first);
    var second = initialMessages();
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
  void initialMessagesCreated() {
    initialMessages();
  }

  @Test
  @TestTransaction
  @Order(1)
  void interactionComplete() throws JsonProcessingException {
    interactionComplete(initialMessages());
  }

  @Test
  @TestTransaction
  @Order(1)
  void interactionFailed() {
    interactionFailed(initialMessages());
  }

  private ServiceErrorAuditEvent interactionFailed(ServiceStartedAuditEvent initialMessagesCreatedEvent) {
    var interactionFailedEvent = ServiceErrorAuditEvent.builder()
                                                       .errorMessage("Some error message")
                                                       .causeErrorMessage("Some cause error message")
                                                       .invocationContext(initialMessagesCreatedEvent.getInvocationContext())
                                                       .build();

    this.repository.persist(interactionFailedEvent);
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

  private ServiceCompleteAuditEvent interactionComplete(ServiceStartedAuditEvent initialMessagesCreatedEvent) throws JsonProcessingException {
    var someObj = new SomeObject("field1", 1);
    var interactionCompleteEvent = ServiceCompleteAuditEvent.builder()
                                                            .result(this.objectMapper.writeValueAsString(someObj))
                                                            .invocationContext(initialMessagesCreatedEvent.getInvocationContext())
                                                            .build();

    this.repository.persist(interactionCompleteEvent);
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

  private ServiceStartedAuditEvent initialMessages() {
    var initialMessagesCreatedEvent = ServiceStartedAuditEvent.builder()
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

    this.repository.persist(initialMessagesCreatedEvent);
    var initialMessagesCreatedEvents = this.repository.find(
			              EVENT_QUERY_TEMPLATE.formatted(ServiceStartedAuditEvent.class.getSimpleName()),
                    initialMessagesCreatedEvent.getInvocationContext().getInteractionId()
             )
            .list();

    assertThat(initialMessagesCreatedEvents)
      .singleElement()
      .usingRecursiveComparison()
      .isEqualTo(initialMessagesCreatedEvent);

    return initialMessagesCreatedEvent;
  }
}