package org.parasol.intake.agent;

import org.parasol.intake.model.EmailType;
import org.parasol.intake.model.MatchedClaim;

/**
 * The one routing rule behind {@link EmailRouter}: the matched claim first, then the label.
 * <p>
 * The router evaluates every activation condition, not only the first true one, so each condition is
 * {@code route(...) == X}: exactly one is true for any input, by construction.
 */
enum EmailRoute {
	EXTRACT,
	FOLLOW_UP,
	UNMATCHED;

	static EmailRoute route(MatchedClaim matchedClaim, EmailType emailType) {
		return matchedClaim.isMatched() ? routeMatched(matchedClaim) : routeUnmatched(emailType);
	}

	private static EmailRoute routeMatched(MatchedClaim matchedClaim) {
		return matchedClaim.isPending() ? EXTRACT : FOLLOW_UP;
	}

	private static EmailRoute routeUnmatched(EmailType emailType) {
		return (emailType == EmailType.NEW_CLAIM) ? EXTRACT : UNMATCHED;
	}
}
