package org.parasol.ui;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.parasol.testing.StubbedModelsTestProfile;

import io.quarkus.test.junit.QuarkusTestProfile;

import io.quarkiverse.quinoa.testing.QuinoaTestProfiles;
import io.quarkiverse.wiremock.devservice.ConnectWireMock;

/**
 * The one profile for every Playwright test: Quinoa's own test-build-and-run-Jest flags
 * ({@link QuinoaTestProfiles.EnableAndRunTests}), plus the {@code claim-intake} model on WireMock
 * ({@link StubbedModelsTestProfile#claimIntakeOnWireMock()}) for the review panel, whose claim only reaches
 * {@code Pending Review} through a real {@code ClaimIntakeFlow} run. Every other model stays real, with its real key and
 * Easy RAG ingestion at boot, because the claim detail page's chat test talks to the real chat bot. Share it rather than adding
 * another: every profile class is another full application boot. {@code @ConnectWireMock} is on the profile rather than the
 * review panel test for the reason {@link StubbedModelsTestProfile} gives.
 * <p>
 *   It also starts Roundcube, the {@code webmail} profile of compose-devservices.yml, for the Roundcube end-to-end test,
 *   on a random host port ({@code ROUNDCUBE_PORT=0}) mapped into {@code parasol.mail.roundcube-port}, so it never clashes
 *   with dev mode's 8000. This override wins over the {@code quarkus.compose.devservices.profiles=none} that surefire and
 *   failsafe set for every other test. Every Playwright boot starts it, which costs a few seconds each.
 * </p>
 */
@ConnectWireMock
public class UiTestProfile implements QuarkusTestProfile {
	private static final Map<String, String> WEBMAIL = Map.of(
		"quarkus.compose.devservices.profiles", "webmail",
		"quarkus.compose.devservices.env-variables.ROUNDCUBE_PORT", "0"
	);

	@Override
	public Map<String, String> getConfigOverrides() {
		return Stream.of(
				new QuinoaTestProfiles.EnableAndRunTests().getConfigOverrides(),
				StubbedModelsTestProfile.claimIntakeOnWireMock(),
				WEBMAIL)
			.flatMap(overrides -> overrides.entrySet().stream())
			.collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
	}
}