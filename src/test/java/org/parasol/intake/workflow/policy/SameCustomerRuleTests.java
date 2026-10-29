package org.parasol.intake.workflow.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.parasol.intake.mailbox.InboundEmail;
import org.parasol.intake.workflow.policy.SameCustomerRule.Holder;

class SameCustomerRuleTests {
	private static final Holder JANE = new Holder("jane.doe@example.com", "Jane Doe");

	@Test
	void theSameAddressWithoutADisplayNameIsTheSameCustomer() {
		assertThat(SameCustomerRule.isSameCustomer(email("jane.doe@example.com", null), JANE)).isTrue();
		assertThat(SameCustomerRule.isSameCustomer(email("jane.doe@example.com", "  "), JANE)).isTrue();
	}

	@Test
	void theAddressIsComparedIgnoringCase() {
		assertThat(SameCustomerRule.isSameCustomer(email("Jane.Doe@Example.com", null), JANE)).isTrue();
	}

	@Test
	void theNameIsComparedOnlyWhenBothArePresentIgnoringCaseAndWhitespace() {
		assertThat(SameCustomerRule.isSameCustomer(email("jane.doe@example.com", "  JANE   doe "), JANE)).isTrue();
		assertThat(SameCustomerRule.isSameCustomer(email("jane.doe@example.com", "John Doe"), JANE)).isFalse();
		assertThat(SameCustomerRule.isSameCustomer(email("jane.doe@example.com", "John Doe"), new Holder("jane.doe@example.com", null))).isTrue();
	}

	@Test
	void anotherAddressIsAnotherCustomer() {
		assertThat(SameCustomerRule.isSameCustomer(email("someone@example.com", "Jane Doe"), JANE)).isFalse();
		assertThat(SameCustomerRule.isSameCustomer(emailWithoutSender(), JANE)).isFalse();
	}

	@Test
	void aPolicyIsInconsistentWhenAnyClaimWithItBelongsToSomeoneElse() {
		var other = new Holder("john@example.com", "John");

		assertThat(SameCustomerRule.isInconsistent(List.of(JANE), email("jane.doe@example.com", null))).isFalse();
		assertThat(SameCustomerRule.isInconsistent(List.of(JANE, other), email("jane.doe@example.com", null))).isTrue();
		assertThat(SameCustomerRule.isInconsistent(List.of(), email("jane.doe@example.com", null))).isFalse();
	}

	private static InboundEmail email(String from, String fromName) {
		return new InboundEmail(
			Optional.of("<id@example.com>"),
			List.of(),
			Optional.of(from),
			Optional.ofNullable(fromName),
			"Subject",
			Instant.EPOCH,
			"Body",
			"Body",
			List.of(),
			List.of(),
			false
		);
	}

	private static InboundEmail emailWithoutSender() {
		return new InboundEmail(Optional.of("<id@example.com>"), List.of(), Optional.empty(), Optional.empty(), "Subject", Instant.EPOCH, "Body", "Body", List.of(), List.of(), false);
	}
}
