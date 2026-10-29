package org.parasol.intake.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.parasol.intake.IntakeFlowTestBase;
import org.parasol.intake.IntakeScenario.Mail;
import org.parasol.intake.model.EmailType;
import org.parasol.testing.StubbedModelsTestProfile;

import io.quarkiverse.wiremock.devservice.ConnectWireMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * An email that names no claim and carries no reply headers, from a sender with pending claims: the resolver routes it, or
 * the customer is asked which claim it's about.
 */
@QuarkusTest
@TestProfile(StubbedModelsTestProfile.class)
@ConnectWireMock
class ClaimIntakeResolveClaimTests extends IntakeFlowTestBase {
	private static final String NO_LOCATION = details("Car hit a post.", "2026-10-07", null, "SINGLE_VEHICLE", null, List.of());
	private static final String LOCATION_ANSWERED = details("Car hit a post.", "2026-10-07", "Elm Street", "SINGLE_VEHICLE", null, List.of("LOCATION"));

	@Test
	void aFreshEmailAboutTheOnlyPendingClaimIsMergedIntoIt() {
		var created = createClaim(SENDER, NO_LOCATION);

		var marker = marker();
		resolve(marker, "EXISTING", created.claim().claimNumber);
		classify(marker, EmailType.CLAIM_FOLLOW_UP);
		extract(marker, LOCATION_ANSWERED);
		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).subject("The location").body("It was on Elm Street. " + marker)));

		assertThat(this.scenario.claim(created.claim().id).status).isEqualTo("Pending Review");
		assertThat(this.scenario.claimCount(SENDER)).isEqualTo(1);
	}

	@Test
	void withTwoPendingClaimsTheRightOneIsChosenAndANewIncidentMakesANewClaim() {
		var first = createClaim(SENDER, NO_LOCATION);

		var secondMarker = marker();
		resolve(secondMarker, "NEW_INCIDENT", null);
		stubNewClaim(secondMarker, details("Hail damage.", "2026-10-08", null, "OTHER", null, List.of()));
		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).subject("Hail").body("Hail damaged my roof. " + secondMarker)));

		var second = this.scenario.awaitClaimOf(SENDER);

		assertThat(second.id).isNotEqualTo(first.claim().id);
		assertThat(second.status).isEqualTo("Pending Information");

		var thirdMarker = marker();
		resolve(thirdMarker, "EXISTING", second.claimNumber);
		classify(thirdMarker, EmailType.CLAIM_FOLLOW_UP);
		extract(thirdMarker, details("Hail damage.", "2026-10-08", "Home", "OTHER", null, List.of("LOCATION")));
		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).subject("Where").body("At home. " + thirdMarker)));

		assertThat(this.scenario.claim(second.id).status).isEqualTo("Pending Review");
		assertThat(this.scenario.claim(first.claim().id).status).isEqualTo("Pending Information");
	}

	@Test
	void anUnsureAnswerAsksWhichClaimListingExactlyTheSendersPendingClaims() {
		var first = createClaim(SENDER, NO_LOCATION);
		var secondMarker = marker();
		resolve(secondMarker, "NEW_INCIDENT", null);
		stubNewClaim(secondMarker, details("Hail damage.", "2026-10-08", null, "OTHER", null, List.of()));
		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).subject("Hail").body("Hail. " + secondMarker)));
		var second = this.scenario.awaitClaimOf(SENDER);
		var other = createClaim("someone.else@example.com", NO_LOCATION);
		var before = this.scenario.claim(first.claim().id).body;

		var marker = marker();
		resolve(marker, "UNSURE", null);
		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).subject("Update").body("Some news. " + marker)));

		assertThat(this.scenario.awaitReplies(SENDER, 3))
			.last()
			.satisfies(reply -> assertThat(reply.subject()).doesNotContain("[CLM"))
			.satisfies(reply -> assertThat(reply.body()).contains(first.claim().claimNumber, second.claimNumber).doesNotContain(other.claim().claimNumber));
		assertThat(this.scenario.claim(first.claim().id).body).isEqualTo(before);
		assertThat(this.scenario.claimCount(SENDER)).isEqualTo(2);
	}

	@Test
	void aClaimOutsideTheOfferedListIsTreatedAsUnsure() {
		var created = createClaim(SENDER, NO_LOCATION);
		var seeded = this.scenario.claim(1L);

		var marker = marker();
		resolve(marker, "EXISTING", seeded.claimNumber);
		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).subject("News").body("Some news. " + marker)));

		assertThat(this.scenario.awaitReplies(SENDER, 2))
			.last()
			.satisfies(reply -> assertThat(reply.body()).contains(created.claim().claimNumber).doesNotContain(seeded.claimNumber));
		assertThat(this.scenario.claim(1L).body).isEqualTo(seeded.body);
	}

	@Test
	void aSenderWithNoPendingClaimsMakesNoResolverCall() {
		var marker = marker();
		stubNewClaim(marker, COMPLETE_DETAILS);

		this.scenario.awaitFiled(this.scenario.submit(Mail.from(SENDER).body("A new accident. " + marker)));

		assertThat(this.stubs.countRequestsFor(RESOLVER)).isZero();
	}

	private void resolve(String marker, String kind, String claimNumber) {
		this.stubs.answerWhenAll(List.of(RESOLVER, marker), """
			{"kind":"%s","claimNumber":%s}""".formatted(kind, quote(claimNumber)));
	}
}
