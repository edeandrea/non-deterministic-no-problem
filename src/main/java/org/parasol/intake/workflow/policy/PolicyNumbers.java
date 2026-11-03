package org.parasol.intake.workflow.policy;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;

import org.parasol.claim.model.Claim;

/**
 * Generates policy numbers for claims whose customer didn't state one, in the seed format: {@code AC-} and nine digits.
 * <p>
 *   It's a checked random draw, not a database sequence: a lone {@code @SequenceGenerator} on the claim would take over
 *   the Panache id, and several claims legitimately share a policy number, so there's no unique constraint to lean on.
 * </p>
 */
final class PolicyNumbers {
	private static final long BOUND = 1_000_000_000L;

	private PolicyNumbers() {
	}

	static String generate(RandomGenerator random, Predicate<String> isTaken) {
		return Stream.generate(() -> format(random.nextLong(BOUND)))
			.filter(number -> !isTaken.test(number))
			.findFirst()
			.orElseThrow();
	}

	static String generateUnused() {
		return generate(ThreadLocalRandom.current(), number -> Claim.count("policyNumber", number) > 0);
	}

	static String format(long digits) {
		return "AC-%09d".formatted(digits);
	}
}
