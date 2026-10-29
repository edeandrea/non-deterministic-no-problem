package org.parasol.intake.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.parasol.intake.observability.FailureStage;

class ProcessingEmailMissingExceptionTests {
	@Test
	void aMissingProcessingEmailIsAStacklessFetchFailure() {
		var failure = new ProcessingEmailMissingException("<missing@example.com>");

		assertThat(failure)
			.hasMessage("No email with Message-ID <missing@example.com> is in the processing folder")
			.hasNoCause();
		assertThat(failure.getStackTrace()).isEmpty();
		assertThat(FailureStage.of(failure)).isEqualTo(FailureStage.FETCH);
	}
}