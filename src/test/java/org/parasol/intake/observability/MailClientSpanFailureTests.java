package org.parasol.intake.observability;

import static io.opentelemetry.api.common.AttributeKey.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.parasol.intake.TestSpans;
import org.parasol.testing.StubbedModelsTestProfile;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import io.opentelemetry.api.trace.StatusCode;

/**
 * A failed mail operation's span records the exception's type and an error status, and nothing of its message, which can name an
 * address or a {@code Message-ID}.
 */
@QuarkusTest
@TestProfile(StubbedModelsTestProfile.class)
class MailClientSpanFailureTests {
	private static final String SECRET = "someone@example.com";

	@Inject
	FailingMailOperation operation;

	@Inject
	TestSpans spans;

	@BeforeEach
	void startCapture() {
		this.spans.startCapture();
	}

	@AfterEach
	void stopCapture() {
		this.spans.stopCapture();
	}

	@Test
	void aFailureIsRecordedByTypeOnly() {
		assertThatThrownBy(() -> this.operation.fail()).isInstanceOf(IllegalStateException.class);

		var span = await()
			.atMost(Duration.ofSeconds(10))
			.until(() -> this.spans.all().stream().filter(s -> s.getName().equals("smtp failing-test")).findFirst(), java.util.Optional::isPresent)
			.orElseThrow();

		assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
		assertThat(span.getStatus().getDescription()).isEqualTo("IllegalStateException");
		assertThat(span.getEvents())
			.singleElement()
			.satisfies(event -> {
				assertThat(event.getName()).isEqualTo("exception");
				assertThat(event.getAttributes().asMap()).hasSize(1);
				assertThat(event.getAttributes().get(stringKey("exception.type"))).isEqualTo(IllegalStateException.class.getName());
		});
		assertThat(span.toString()).doesNotContain(SECRET);
	}

	@ApplicationScoped
	public static class FailingMailOperation {
		@MailClientSpan(protocol = MailClientSpan.Protocol.SMTP, operation = "failing-test")
		public void fail() {
			throw new IllegalStateException("Couldn't deliver to " + SECRET);
		}
	}
}
