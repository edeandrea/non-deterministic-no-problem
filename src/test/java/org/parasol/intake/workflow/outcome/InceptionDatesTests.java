package org.parasol.intake.workflow.outcome;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Random;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class InceptionDatesTests {
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 11);

	@Test
	void theDateIsStrictlyBeforeTheIncidentAndWithinAboutFiveYears() {
		var incident = LocalDate.of(2026, 10, 7);

		assertThat(IntStream.range(0, 500).mapToObj(seed -> InceptionDates.before(incident, new Random(seed))))
			.allSatisfy(date -> assertThat(date).isBefore(incident).isAfterOrEqualTo(incident.minusYears(6)));
	}

	@Test
	void theDateIsStrictlyBeforeToday() {
		assertThat(IntStream.range(0, 500).mapToObj(seed -> InceptionDates.before(TODAY, new Random(seed))))
			.allSatisfy(date -> assertThat(date).isBefore(TODAY));
	}
}
