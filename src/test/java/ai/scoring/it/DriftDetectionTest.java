package ai.scoring.it;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import io.quarkus.test.junit.EnabledIfApplicationProperty;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;

@Target({ TYPE, ANNOTATION_TYPE })
@Retention(RUNTIME)
@Documented
@Inherited
@EnabledIfApplicationProperty(
	named = "quarkus.aiscoring.interaction-mode",
	matches = "drift-detection",
	disabledReason = "Drift detection is not enabled"
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
