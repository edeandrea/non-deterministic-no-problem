package org.parasol.intake.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.Test;
import org.parasol.claim.model.CorrespondenceDirection;
import org.parasol.intake.IntakeFlowTestBase;
import org.parasol.intake.IntakeScenario;
import org.parasol.intake.IntakeScenario.Mail;
import org.parasol.intake.mailbox.MailFolder;

import io.quarkus.test.junit.QuarkusTest;
import io.serverlessworkflow.impl.WorkflowStatus;

/**
 * What a new leader does with an email the dead one left in the processing folder, one test per row of how far its run got. The state is built
 * from a real run, then rewound to how it was when the run died.
 */
@QuarkusTest
class ProcessingRecoveryTests extends IntakeFlowTestBase {
	@Test
	void anEmailNeverAcceptedTakesTheNormalPath() {
		var marker = marker();
		stubNewClaim(marker, COMPLETE_DETAILS);
		var email = this.scenario.deliverToProcessing(Mail.from(SENDER).name(SENDER_NAME).subject("Car accident").body("First email. " + marker));
		var messageId = email.messageId().orElseThrow();

		this.scenario.recover(email);
		this.scenario.awaitFiled(messageId);

		assertThat(this.scenario.claimCount(SENDER)).isEqualTo(1);
		assertThat(this.scenario.awaitReplies(SENDER, 1)).hasSize(1);
		assertThat(this.scenario.recordedRows(messageId)).isEqualTo(1);
	}

	@Test
	void anEmailAcceptedButNotAppliedRunsAgainOnceDespiteBeingRecorded() {
		var marker = marker();
		stubNewClaim(marker, COMPLETE_DETAILS);
		var email = this.scenario.deliverToProcessing(Mail.from(SENDER).name(SENDER_NAME).subject("Car accident").body("First email. " + marker));
		var messageId = email.messageId().orElseThrow();
		this.scenario.recordAccepted(email);

		this.scenario.recover(email);
		this.scenario.awaitFiled(messageId);

		assertThat(this.scenario.claimCount(SENDER)).isEqualTo(1);
		assertThat(this.scenario.awaitReplies(SENDER, 1)).hasSize(1);
		assertThat(this.scenario.recordedRows(messageId)).isEqualTo(1);
	}

	@Test
	void anEmailAppliedAndAnsweredIsOnlyFiledAndCountedOnce() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		var claimId = created.claim().id;
		this.scenario.awaitWaitingRun(claimId);
		var replies = this.scenario.awaitReplies(SENDER, 1).size();
		var email = this.scenario.backToProcessing(created.messageId());
		var before = counted("claim.intake.emails", "outcome", "new_claim");

		this.scenario.recover(email);
		this.scenario.awaitFiled(created.messageId());

		awaitCounted(before + 1, "claim.intake.emails", "outcome", "new_claim");
		assertThat(this.scenario.replies(SENDER)).hasSize(replies);
		assertThat(this.scenario.claimCount(SENDER)).isEqualTo(1);
		assertThat(this.scenario.awaitWaitingRun(claimId).status()).isEqualTo(WorkflowStatus.WAITING);
	}

	@SuppressWarnings("resource")
	@Test
	void anEmailAppliedButNotAnsweredResumesAtTheReplyThenFilesAndWaitsForTheReview() {
		var created = createClaim(SENDER, COMPLETE_DETAILS);
		var claimId = created.claim().id;
		var deadRun = this.scenario.awaitWaitingRun(claimId);
		var firstReply = this.scenario.awaitReplies(SENDER, 1).getFirst();

		// The run died after applying the outcome, before its reply was recorded
		deadRun.cancel();
		await().atMost(IntakeScenario.WAIT).until(() -> deadRun.status() != WorkflowStatus.WAITING);
		this.scenario.forgetReplies(claimId);
		var email = this.scenario.backToProcessing(created.messageId());

		this.scenario.recover(email);
		this.scenario.awaitFiled(created.messageId());

		var replies = this.scenario.awaitReplies(SENDER, 2);
		var run = this.scenario.awaitWaitingRun(claimId);

		assertThat(replies).hasSize(2);
		assertThat(replies.getLast().subject()).isEqualTo(firstReply.subject());
		assertThat(replies.getLast().body()).isEqualTo(firstReply.body());
		assertThat(this.scenario.claimCount(SENDER)).isEqualTo(1);
		assertThat(run.id()).isNotEqualTo(deadRun.id());
		assertThat(this.scenario.intake(claimId).orElseThrow().reviewRunId).isEqualTo(run.id());
		assertThat(this.scenario.transcript(claimId))
			.filteredOn(entry -> entry.direction() == CorrespondenceDirection.OUTBOUND)
			.hasSize(1);
		assertThat(this.scenario.inFolder(MailFolder.PROCESSING)).isEmpty();
	}
}
