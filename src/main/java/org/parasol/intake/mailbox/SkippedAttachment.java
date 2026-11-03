package org.parasol.intake.mailbox;

/**
 * An attachment of an inbound email that isn't kept, and why. The reply tells the customer about it.
 *
 * @param fileName The attachment's file name, or a generated one if the email didn't give one
 * @param contentType The attachment's media type, without parameters
 * @param reason Why it was skipped
 */
public record SkippedAttachment(String fileName, String contentType, Reason reason) {
	/**
	 * Why an attachment was skipped.
	 */
	public enum Reason {
		/**
		 * Not one of the allowed image formats (JPEG, PNG, GIF, WebP). SVG is deliberately not allowed.
		 */
		NOT_AN_IMAGE,

		/**
		 * An image larger than the configured maximum size.
		 */
		TOO_LARGE,

		/**
		 * An image beyond the configured maximum count.
		 */
		TOO_MANY,

		/**
		 * An image with no data.
		 */
		EMPTY
	}
}