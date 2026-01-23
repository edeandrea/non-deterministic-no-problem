package org.parasol.ai.scoring.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.parasol.ai.scoring.domain.event.InteractionCompletedEvent;
import org.parasol.ai.scoring.domain.event.InteractionStartedEvent;
import org.parasol.ai.scoring.domain.event.InvocationContext;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@TestTransaction
class InteractionEventRepositoryTests {
	private static final String EVENT_QUERY_TEMPLATE = "from InteractionEvent e WHERE type(e) = %s AND invocationContext.interactionId = ?1";
	private static final Instant NOW = Instant.now();
	
	@Inject
	InteractionEventRepository repository;
	
	@Inject
	ObjectMapper objectMapper;
	
	private record SomeObject(String field1, int field2) { }

  @Test
  void serviceStartedCreated() {
    interactionStarted();
  }

  @Test
  void interactionComplete() throws JsonProcessingException {
    interactionComplete(interactionStarted());
  }

	@Test
	void deleteAllForInteractionId() throws JsonProcessingException {
		assertNumberOfInteractions(0);
		assertThat(this.repository.count()).isZero();

    var first = interactionComplete(interactionStarted());
		assertNumberOfInteractions(2);

		var second = interactionComplete(interactionStarted());
		assertNumberOfInteractions(4);

		this.repository.deleteAllForInteractionId(first.getInvocationContext().getInteractionId());
		assertNumberOfInteractions(2);
		assertThat(this.repository.findByIdOptional(first.getId())).isEmpty();

		this.repository.deleteAllForInteractionId(second.getInvocationContext().getInteractionId());
		assertNumberOfInteractions(0);
		assertThat(this.repository.findByIdOptional(second.getId())).isEmpty();
	}

	@Test
	void getCorrelatedStartedEvent() throws JsonProcessingException {
		assertNumberOfInteractions(0);

		var started = interactionStarted();
		var completed = interactionComplete(started);
		assertNumberOfInteractions(2);

		assertThat(this.repository.getCorrelatedStartedEvent(completed))
			.get()
			.usingRecursiveComparison()
			.isEqualTo(started);
	}

	private void assertNumberOfInteractions(int expected) {
		assertThat(this.repository.count()).isEqualTo(expected);
	}

  private InteractionCompletedEvent interactionComplete(InteractionStartedEvent interactionStartedEvent) throws JsonProcessingException {
    var someObj = new SomeObject("field1", 1);
    var interactionCompleteEvent = InteractionCompletedEvent.builder()
                                                            .result(this.objectMapper.writeValueAsString(someObj))
                                                            .invocationContext(interactionStartedEvent.getInvocationContext().toBuilder().build())
                                                            .build();

    this.repository.persistAndFlush(interactionCompleteEvent);
    var interactionCompleteEvents = this.repository.find(
							EVENT_QUERY_TEMPLATE.formatted(InteractionCompletedEvent.class.getSimpleName()),
              interactionCompleteEvent.getInvocationContext().getInteractionId()
            )
            .list();

    assertThat(interactionCompleteEvents)
      .singleElement()
      .usingRecursiveComparison()
      .isEqualTo(interactionCompleteEvent);

    assertThat(interactionCompleteEvents.getFirst())
	    .isNotNull()
	    .isInstanceOf(InteractionCompletedEvent.class).extracting(e -> {
        try {
	        return this.objectMapper.readValue(((InteractionCompletedEvent) e).getResult(), SomeObject.class);
        } catch (JsonProcessingException ex) {
	        throw new RuntimeException(ex);
        }
      })
	    .usingRecursiveComparison()
	    .isEqualTo(someObj);

    return interactionCompleteEvent;
  }

  private InteractionStartedEvent interactionStarted() {
    var interactionStartedEvent = InteractionStartedEvent.builder()
                                                         .systemMessage("System message")
                                                         .userMessage("User message")
                                                         .invocationContext(
							 InvocationContext.builder()
							                  .interactionDate(NOW)
								                .interactionId(UUID.randomUUID())
							                  .interfaceName("someInterface")
							                  .methodName("someMethod")
							                  .applicationName("application")
							                  .build()
             )
                                                         .build();

    this.repository.persistAndFlush(interactionStartedEvent);
    var serviceStartedEvents = this.repository.find(
			EVENT_QUERY_TEMPLATE.formatted(InteractionStartedEvent.class.getSimpleName()), interactionStartedEvent.getInvocationContext().getInteractionId())
                                              .list();

    assertThat(serviceStartedEvents)
      .singleElement()
      .usingRecursiveComparison()
      .isEqualTo(interactionStartedEvent);

    return interactionStartedEvent;
  }
}