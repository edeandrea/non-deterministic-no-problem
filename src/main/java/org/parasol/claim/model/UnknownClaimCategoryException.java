package org.parasol.claim.model;

/**
 * Thrown when a value doesn't match any {@link ClaimCategory} label or constant name.
 */
public class UnknownClaimCategoryException extends IllegalArgumentException {
	/**
	 * Creates the exception for the value that couldn't be resolved.
	 *
	 * @param value the unresolvable value (may be {@code null})
	 */
	public UnknownClaimCategoryException(String value) {
		super("Unknown claim category: %s".formatted(value));
	}

	// IllegalArgumentException has no constructor to skip the trace. This is an expected outcome that is mapped to a
	// response, so no stack trace is ever shown or logged
	@Override
	public Throwable fillInStackTrace() {
		return this;
	}
}
