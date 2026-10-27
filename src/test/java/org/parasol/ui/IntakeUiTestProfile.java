package org.parasol.ui;

import java.util.LinkedHashMap;
import java.util.Map;

import org.parasol.intake.IntakeTestProfile;

/**
 * {@link IntakeTestProfile}, plus Quinoa's own test-build-and-run-Jest flags
 * ({@code io.quarkiverse.quinoa.testing.QuinoaTestProfiles.EnableAndRunTests}), for the one Playwright suite that needs a
 * real intake run behind the compiled UI (the review panel: a claim only reaches {@code Pending Review} through the real
 * {@code ClaimIntakeFlow}). One more full application boot than either profile pays for on its own; reuse this rather than
 * adding a further variant.
 */
public class IntakeUiTestProfile extends IntakeTestProfile {
	@Override
	public Map<String, String> getConfigOverrides() {
		var overrides = new LinkedHashMap<>(super.getConfigOverrides());
		overrides.put("quarkus.quinoa", "true");
		overrides.put("quarkus.quinoa.run-tests", "true");

		return overrides;
	}
}
