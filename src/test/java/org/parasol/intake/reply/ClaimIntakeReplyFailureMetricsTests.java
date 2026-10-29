package org.parasol.intake.reply;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.parasol.intake.IntakeFlowTestBase;
import org.parasol.intake.IntakeScenario;
import org.parasol.intake.IntakeScenario.Mail;
import org.parasol.intake.failure.IntakeFailureHandler;
import org.parasol.testing.StubbedModelsTestProfile;

import io.micrometer.core.instrument.Counter;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;

/**
 * A run whose reply can't be sent is a {@code reply} failure. It sits in this package because {@link ReplyFailedException}'s
 * constructor is package-private: the sender throws it, nothing else does.
 */
@QuarkusTest
@TestProfile(StubbedModelsTestProfile.class)
class ClaimIntakeReplyFailureMetricsTests extends IntakeFlowTestBase {
	private static final String NO_LOCATION = details("Car hit a post.", "2026-10-07", null, "SINGLE_VEHICLE", null, List.of());

	@InjectSpy
	IntakeReplySender sender;

	@InjectSpy
	IntakeFailureHandler failureHandler;

	@Test
	void aReplyThatCouldNotBeSentIsCountedAsFatalInTheReplyStage() {
		var failures = this.meters.find("claim.intake.failures")
			.tags("stage", "reply", "outcome", "fatal")
			.counters()
			.stream()
			.collect(Collectors.toMap(counter -> counter.getId().getTag("exception"), Counter::count));
		var marker = marker();
		stubNewClaim(marker, NO_LOCATION);
		// Only the first send fails: the failure path's own reply to the customer must still go out
		doThrow(new ReplyFailedException(new IllegalStateException("The SMTP server is down")))
			.doCallRealMethod()
			.when(this.sender)
			.sendTemplateReply(any(), any(), any(), any(), any());

		var id = this.scenario.submit(Mail.from(SENDER).subject("Car accident").body("My claim. " + marker));
		this.scenario.awaitFailed(id);

		Awaitility.await()
			.atMost(IntakeScenario.WAIT)
			.untilAsserted(() -> {
				// Flow can wrap the injected failure; use the class actually delivered to the failure boundary.
				var cause = ArgumentCaptor.forClass(Throwable.class);
				verify(this.failureHandler).handle(eq(id), cause.capture());
				var exception = cause.getValue().getClass().getSimpleName();
				assertThat(counted("claim.intake.failures", "stage", "reply", "exception", exception, "outcome", "fatal"))
					.isEqualTo(failures.getOrDefault(exception, 0.0) + 1);
			});
	}
}
