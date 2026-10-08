package org.parasol.intake.reply;

import java.util.Optional;
import java.util.regex.Pattern;

import org.parasol.claim.model.Claim;
import org.parasol.intake.mailbox.InboundEmail;

/**
 * Picks the name a reply greets the customer by.
 * <ol>
 *   <li>The claim's {@code clientName}, when the email resolved to a claim and was sent from that claim's address: we
 *   know who they are.</li>
 *   <li>Otherwise the email's {@code From} display name, when it has one that isn't just an email address. It's what the
 *   sender called themselves, so it discloses nothing.</li>
 *   <li>Otherwise none, and the template greets "Customer".</li>
 * </ol>
 * The claim's name is never used for an email from another address: that would tell whoever sent it who the claim
 * belongs to. That's why the no-matching-claim reply always passes no claim.
 */
public final class CustomerName {
	// Something@something: a display name that's just an address (e.g. "jane@example.com" <jane@example.com>)
	private static final Pattern EMAIL_ADDRESS = Pattern.compile("\\S+@\\S+");

	private CustomerName() {
	}

	/**
	 * The name to greet the customer by.
	 *
	 * @param email The customer's email
	 * @param claim The claim the email resolved to, if any. Leave it empty for replies that must not disclose a claim
	 * @return The name, or empty to fall back to the template's generic greeting
	 */
	public static Optional<String> forReply(InboundEmail email, Optional<Claim> claim) {
		return claim
			.filter(resolved -> isFromClaimAddress(email, resolved))
			.flatMap(resolved -> usable(resolved.clientName))
			.or(() -> email.fromName()
				.flatMap(CustomerName::usable)
				.filter(name -> !EMAIL_ADDRESS.matcher(name).matches()));
	}

	private static boolean isFromClaimAddress(InboundEmail email, Claim claim) {
		return email.fromAddress()
			.filter(address -> address.equalsIgnoreCase(claim.emailAddress))
			.isPresent();
	}

	private static Optional<String> usable(String name) {
		return Optional.ofNullable(name)
			.map(String::strip)
			.filter(stripped -> !stripped.isEmpty());
	}
}
