package org.parasol.intake.workflow.policy;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;

import org.parasol.intake.mailbox.InboundEmail;

/**
 * The "same customer" rule for a policy number a customer states.
 * <p>
 *   The email address has to match (case-insensitive). The name is compared only when both the sender's display name and
 *   the claim's client name are present, ignoring case and whitespace, so a missing display name still matches on the
 *   address alone.
 * </p>
 */
final class SameCustomerRule {
	private SameCustomerRule() {
	}

	/**
	 * The customer of an existing claim that has the stated policy number.
	 */
	record Holder(String emailAddress, String clientName) {
	}

	static boolean isSameCustomer(InboundEmail email, Holder holder) {
		var sameAddress = email.fromAddress()
			.filter(address -> address.equalsIgnoreCase(holder.emailAddress()))
			.isPresent();
		var sameName = email.fromName()
			.map(SameCustomerRule::normalize)
			.filter(name -> !name.isEmpty())
			.flatMap(name -> Optional.ofNullable(holder.clientName())
				.map(SameCustomerRule::normalize)
				.filter(clientName -> !clientName.isEmpty())
				.map(name::equals))
			.orElse(true);

		return sameAddress && sameName;
	}

	/**
	 * Whether any claim with the stated policy number belongs to a different customer.
	 */
	static boolean isInconsistent(Collection<Holder> holders, InboundEmail email) {
		return holders.stream()
			.anyMatch(holder -> !isSameCustomer(email, holder));
	}

	private static String normalize(String name) {
		return String.join(" ", name.strip().split("\\s+")).toLowerCase(Locale.ROOT);
	}
}
