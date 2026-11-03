package org.parasol.intake.workflow.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Random;

import org.junit.jupiter.api.Test;

class PolicyNumbersTests {
	@Test
	void aNumberIsAcAndNineDigitsPaddedWithZeros() {
		assertThat(PolicyNumbers.format(42)).isEqualTo("AC-000000042");
		assertThat(PolicyNumbers.format(987654321)).isEqualTo("AC-987654321");
	}

	@Test
	void aTakenNumberIsRedrawn() {
		var taken = new HashSet<String>();
		var first = PolicyNumbers.generate(new Random(7), _ -> false);
		taken.add(first);

		var second = PolicyNumbers.generate(new Random(7), taken::contains);

		assertThat(second).matches("AC-\\d{9}").isNotEqualTo(first);
	}
}
