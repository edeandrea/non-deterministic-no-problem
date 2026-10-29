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
 */
@ConnectWireMock
public class UiTestProfile implements QuarkusTestProfile {
	@Override
	public Map<String, String> getConfigOverrides() {
		return Stream.concat(
				new QuinoaTestProfiles.EnableAndRunTests().getConfigOverrides().entrySet().stream(),
				StubbedModelsTestProfile.claimIntakeOnWireMock().entrySet().stream())
			.collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
	}
}