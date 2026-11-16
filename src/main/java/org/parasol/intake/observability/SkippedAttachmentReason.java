package org.parasol.intake.observability;

import java.util.Locale;

/**
 * Why the intake skipped an attachment. The lower-case constant name is the {@code reason} tag of
 * {@code claim.intake.attachments} for a skipped one.
 */
public enum SkippedAttachmentReason {
	/**
	 * Not one of the allowed image formats.
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
	EMPTY;

	String tag() {
		return name().toLowerCase(Locale.ROOT);
	}
}
