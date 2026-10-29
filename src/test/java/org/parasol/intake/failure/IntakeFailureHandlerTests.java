package org.parasol.intake.failure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.parasol.intake.IntakeScenario.Mail;

import org.parasol.intake.IntakeFlowTestBase;
import org.parasol.intake.mailbox.MailFolder;
import org.parasol.testing.StubbedModelsTestProfile;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * The failure path before a run waits: the email goes to the failed folder and the sender gets the processing-problem
 * reply. (Faults after the review wait are in {@code ClaimIntakeReviewTests}.)
 */
@QuarkusTest
@TestProfile(StubbedModelsTestProfile.class)
class IntakeFailureHandlerTests extends IntakeFlowTestBase {
	@Test
	void anEmailWithoutAMessageIdIsFailedWithAProblemReplyAndNoRun() {
		this.scenario.submit(Mail.from(SENDER).subject("No id").withoutMessageId().body("Hello"));

		Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> this.scenario.inFolder(MailFolder.FAILED).size() == 1);

		assertThat(this.scenario.awaitReplies(SENDER, 1))
			.singleElement()
			.satisfies(reply -> assertThat(reply.firstHeader("In-Reply-To")).isEmpty())
			.satisfies(reply -> assertThat(reply.firstHeader("References")).isEmpty());
		assertThat(this.scenario.instanceRows()).isZero();
		assertThat(this.scenario.claimCount()).isEqualTo(6);
	}

	@Test
	void anAgentFailureFilesTheEmailAsFailedAndSendsAProblemReply() {
		var marker = marker();
		this.stubs.failWhenAll(List.of(CLASSIFY, marker));

		var id = this.scenario.submit(Mail.from(SENDER).body("Something. " + marker));

		this.scenario.awaitFailed(id);

		assertThat(this.scenario.awaitReplies(SENDER, 1)).hasSize(1);
		assertThat(this.scenario.claimCount()).isEqualTo(6);
	}
}
