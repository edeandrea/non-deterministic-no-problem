package ai.scoring.it;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.condition.EnabledIfConfig;

@Target({ TYPE, ANNOTATION_TYPE })
@Retention(RUNTIME)
@Documented
@Inherited
@EnabledIfConfig(
	named = "quarkus.aiscoring.interaction-mode",
	matches = "drift-detection",
	disabledReason = "Drift detection is not enabled because interaction-mode != 'drift-detection'")
@EnabledIfSystemProperty(
	named = "quarkus.profile",
	matches = "drift",
	disabledReason = "Drift detection is not enabled because profile != 'drift'")
@EnabledIfEnvironmentVariable(
	named = "OPENAI_API_KEY",
	matches = ".+",
	disabledReason = "Drift detection is not enabled because OPENAI_API_KEY is not set")
@EnabledIfEnvironmentVariable(
	named = "COHERE_API_KEY",
	matches = ".+",
	disabledReason = "Drift detection is not enabled because COHERE_API_KEY is not set"
)
@QuarkusTest
public @interface DriftDetectionTest {
	class DriftTestProfile implements QuarkusTestProfile {
		@Override
		public String getConfigProfile() {
			return "drift";
		}
	}
}
