package org.parasol.intake.agent.triage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.parasol.intake.ChatStubs;
import org.parasol.intake.IntakeAgentsTestProfile;
import org.parasol.intake.MissingItem;
import org.parasol.intake.model.ClaimResolution;
import org.parasol.intake.model.ClaimResolution.Kind;
import org.parasol.intake.model.PendingClaim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import dev.langchain4j.model.output.structured.Description;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkiverse.wiremock.devservice.ConnectWireMock;

@QuarkusTest
@TestProfile(IntakeAgentsTestProfile.class)
@ConnectWireMock
class ClaimResolverTests {
	private static final String RESOLVE = "Decide which of the customer's pending claims the following email is about";
	private static final List<PendingClaim> PENDING = List.of(
		new PendingClaim("CLM01000000", "A car hit a post on Elm Street.", Set.of(MissingItem.INCIDENT_DATE)),
		new PendingClaim("CLM01001009", "A stolen bicycle rack.", Set.of()));
	private static final List<String> OFFERED = List.of("CLM01000000", "CLM01001009");

	@Inject
	ClaimResolver resolver;

	@Inject
	ObjectMapper objectMapper;

	WireMock wiremock;

	ChatStubs stubs;

	@BeforeEach
	void setUp() {
		this.stubs = new ChatStubs(this.wiremock, this.objectMapper);
		this.stubs.reset();
	}

	@Test
	void picksTheRightClaimOfTwoFromThePendingClaimsItWasGiven() {
		this.stubs.answer(RESOLVE, """
			{"kind":"EXISTING","claimNumber":"CLM01001009"}""");

		assertThat(this.resolver.resolveClaim("About the bike rack: it was taken on Monday.", PENDING).limitToOfferedClaims(OFFERED))
			.isEqualTo(new ClaimResolution(Kind.EXISTING, "CLM01001009"));
		assertThat(this.stubs.requests())
			.singleElement()
			.satisfies(request -> assertThat(this.stubs.roles(request)).containsExactly("system", "user"))
			.satisfies(request -> assertThat(this.stubs.userMessage(request))
				.contains("- CLM01000000: A car hit a post on Elm Street. (we asked for: incident date)")
				.contains("- CLM01001009: A stolen bicycle rack. (we asked for: nothing)"));
	}

	@Test
	void theRequestCarriesEveryKindWithWhatItMeansFromTheRecord() throws NoSuchFieldException {
		this.stubs.answer(RESOLVE, """
			{"kind":"UNSURE","claimNumber":null}""");

		this.resolver.resolveClaim("Any news?", PENDING);

		var kindDescription = ClaimResolution.class.getDeclaredField("kind").getAnnotation(Description.class).value()[0];
		var kinds = Stream.of(Kind.values())
			.map(Kind::name)
			.toList();
		assertThat(this.stubs.userMessage(this.stubs.requestFor(RESOLVE)))
			.as("LangChain4j's JSON format line, built from ClaimResolution")
			.contains("\"kind\": (%s; type: enum, must be one of %s)".formatted(kindDescription, kinds));
	}

	@Test
	void aNewIncident() {
		this.stubs.answer(RESOLVE, """
			{"kind":"NEW_INCIDENT","claimNumber":null}""");

		assertThat(this.resolver.resolveClaim("Someone backed into my car today.", PENDING).limitToOfferedClaims(OFFERED))
			.isEqualTo(new ClaimResolution(Kind.NEW_INCIDENT, null));
	}

	@Test
	void unsure() {
		this.stubs.answer(RESOLVE, """
			{"kind":"UNSURE","claimNumber":null}""");

		assertThat(this.resolver.resolveClaim("Any news?", PENDING).limitToOfferedClaims(OFFERED))
			.isEqualTo(new ClaimResolution(Kind.UNSURE, null));
	}

	@Test
	void anInventedClaimNumberIsRejected() {
		this.stubs.answer(RESOLVE, """
			{"kind":"EXISTING","claimNumber":"CLM01005045"}""");

		assertThat(this.resolver.resolveClaim("This is about CLM01005045.", PENDING).limitToOfferedClaims(OFFERED))
			.isEqualTo(new ClaimResolution(Kind.UNSURE, null));
	}
}
