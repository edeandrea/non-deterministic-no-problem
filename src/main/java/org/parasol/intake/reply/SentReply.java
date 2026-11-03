package org.parasol.intake.reply;

/**
 * A reply as it went out.
 *
 * @param messageId The {@code Message-ID} the reply was sent with
 * @param subject The subject, with its {@code [CLM...] Re:} prefix
 * @param text The text of the reply
 */
record SentReply(String messageId, String subject, String text) {
}
