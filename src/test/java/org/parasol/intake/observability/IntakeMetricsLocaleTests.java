package org.parasol.intake.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.parasol.intake.observability.IntakeActivity.ReviewDecisionApplied;
import org.parasol.intake.review.ReviewOutcome;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * A tag computed from an enum constant is the same whatever the JVM's default locale: in Turkish, {@code "I".toLowerCase()} is a dotless
 * {@code "ı"}, so {@code NEEDS_INFORMATION} would become {@code needs_ınformatıon}.
 */
class IntakeMetricsLocaleTests {
	@Test
	void aReviewDecisionIsTaggedTheSameWhateverTheDefaultLocale() {
		var registry = new SimpleMeterRegistry();
		var metrics = new IntakeMetrics(registry);
		var defaultLocale = Locale.getDefault();

		try {
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			metrics.onReviewDecisionApplied(new ReviewDecisionApplied(ReviewOutcome.NEEDS_INFORMATION, ReviewAgreement.OVERRIDDEN, Optional.empty()));
		}
		finally {
			Locale.setDefault(defaultLocale);
		}

		assertThat(registry.find("claim.intake.review.decisions").tag("decision", "needs_information").counter())
			.isNotNull()
			.satisfies(counter -> assertThat(counter.count()).isEqualTo(1));
	}
}