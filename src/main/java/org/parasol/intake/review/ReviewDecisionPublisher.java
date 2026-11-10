package org.parasol.intake.review;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.serverlessworkflow.impl.WorkflowApplication;

/**
 * Publishes a reviewer's decision as the CloudEvent the waiting intake run listens for. This is the local leg: it reaches only runs
 * hosted in this JVM, so the review's decision isn't published from here directly but written as a signal and handed over by
 * {@code RunSignalRelay} on the application instance that hosts the run.
 * <p>
 *   A run that hasn't reached its wait yet misses the event, so the relay republishes until the run has taken it.
 * </p>
 */
@ApplicationScoped
public class ReviewDecisionPublisher {
	/**
	 * The CloudEvent type the intake run waits for.
	 */
	public static final String REVIEW_DECIDED = "org.parasol.intake.review.decided";

	private static final URI SOURCE = URI.create("/parasol/intake/review");
	private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(30);

	private final WorkflowApplication workflowApplication;
	private final ObjectMapper objectMapper;

	ReviewDecisionPublisher(WorkflowApplication workflowApplication, ObjectMapper objectMapper) {
		this.workflowApplication = workflowApplication;
		this.objectMapper = objectMapper;
	}

	/**
	 * Publishes the decision to every intake run that's waiting. Only the run named in the event reacts to it.
	 *
	 * @param decided The decision, with the claim and run it's for
	 */
	public void publish(ReviewDecided decided) {
		var event = CloudEventBuilder.v1()
			.withId(UUID.randomUUID().toString())
			.withSource(SOURCE)
			.withType(REVIEW_DECIDED)
			.withDataContentType("application/json")
			.withData(toJson(decided))
			.build();

		this.workflowApplication.eventPublishers()
			.forEach(publisher -> publisher.publish(event).toCompletableFuture().orTimeout(PUBLISH_TIMEOUT.toSeconds(), TimeUnit.SECONDS).join());
	}

	private byte[] toJson(ReviewDecided decided) {
		try {
			return this.objectMapper.writeValueAsBytes(decided);
		}
		catch (JsonProcessingException e) {
			throw new IllegalStateException("Couldn't serialize the review decision", e);
		}
	}
}
