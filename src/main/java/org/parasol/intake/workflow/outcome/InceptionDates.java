package org.parasol.intake.workflow.outcome;

import java.time.LocalDate;
import java.util.random.RandomGenerator;

/**
 * Picks the inception date of a new claim's policy. The email doesn't say, so it's made up: random, and strictly before
 * the incident (before today when the incident date is unknown), at most about five years back.
 */
final class InceptionDates {
	private static final int MAX_DAYS_BEFORE = 5 * 365;

	private InceptionDates() {
	}

	/**
	 * A random date strictly before the given one, at most about five years back.
	 *
	 * @param limit The incident date, or today when it's unknown
	 * @param random The source of randomness
	 * @return The inception date
	 */
	static LocalDate before(LocalDate limit, RandomGenerator random) {
		return limit.minusDays(1 + random.nextInt(MAX_DAYS_BEFORE));
	}
}
