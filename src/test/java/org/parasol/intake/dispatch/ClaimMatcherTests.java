package org.parasol.intake.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.parasol.intake.IntakeScenario;
import org.parasol.intake.mailbox.InboundEmail;
import org.parasol.testing.StubbedModelsTestProfile;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Matching an email to a claim, and which conversation id the run starts in. Uses the first seeded claim.
 */
@QuarkusTest
@TestProfile(StubbedModelsTestProfile.class)
class ClaimMatcherTests {
	private static final long CLAIM_ID = 1L;
	private static final String OWNER = "marty.mcfly@email.com";

	@Inject
	ClaimMatcher matcher;

	@Inject
	IntakeScenario scenario;

	@AfterEach
	void cleanUp() {
		this.scenario.cleanUp();
	}

	@Test
	void aClaimNumberFromTheOwnerMatchesAndReusesTheStoredConversation() {
		var number = claimNumber();
		this.scenario.createIntakeState(CLAIM_ID, "stored-conversation");

		assertThat(this.matcher.match(email(OWNER, "About " + number, List.of())))
			.returns(CLAIM_ID, ClaimMatcher.ClaimMatch::claimId)
			.returns(true, ClaimMatcher.ClaimMatch::senderMatches)
			.returns("stored-conversation", ClaimMatcher.ClaimMatch::conversationId);
	}

	@Test
	void aSeededClaimWithNoStoredConversationGetsANewOne() {
		var match = this.matcher.match(email(OWNER, "About " + claimNumber(), List.of()));

		assertThat(match.senderMatches()).isTrue();
		assertThat(match.conversationId()).isNotBlank();
	}

	@Test
	void anotherSendersEmailNamesTheClaimButDoesNotGetItsConversation() {
		this.scenario.createIntakeState(CLAIM_ID, "stored-conversation");

		assertThat(this.matcher.match(email("stranger@example.com", "About " + claimNumber(), List.of())))
			.returns(CLAIM_ID, ClaimMatcher.ClaimMatch::claimId)
			.returns(false, ClaimMatcher.ClaimMatch::senderMatches)
			.satisfies(match -> assertThat(match.conversationId()).isNotEqualTo("stored-conversation"));
	}

	@Test
	void replyHeadersMatchThroughTheRecordedMessageIds() {
		this.scenario.recordCorrespondence("<earlier@example.com>", CLAIM_ID);

		assertThat(this.matcher.match(email(OWNER, "No number here", List.of("<unrelated@example.com>", "<earlier@example.com>"))))
			.returns(CLAIM_ID, ClaimMatcher.ClaimMatch::claimId)
			.returns(true, ClaimMatcher.ClaimMatch::senderMatches);
	}

	@Test
	void anEmailNamingNoClaimMatchesNone() {
		assertThat(this.matcher.match(email(OWNER, "Hello", List.of("<nobody@example.com>"))))
			.returns(null, ClaimMatcher.ClaimMatch::claimId)
			.returns(false, ClaimMatcher.ClaimMatch::senderMatches);
	}

	private String claimNumber() {
		return this.scenario.claim(CLAIM_ID).claimNumber;
	}

	private static InboundEmail email(String from, String subject, List<String> references) {
		return new InboundEmail(Optional.of("<new@example.com>"), references, Optional.of(from), Optional.empty(), subject, Instant.EPOCH, Instant.EPOCH, 4, "Body", "Body", List.of(), List.of(), false);
	}
}
