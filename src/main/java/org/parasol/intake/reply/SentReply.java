package org.parasol.intake.reply;

/**
 * A reply as it went out.
 *
 * @param messageId The {@code Message-ID} the reply was sent with
 * @param subject The subject, with its {@code [CLM...] Re:} prefix
 * @param text The text of the reply
 * @param inReplyTo The {@code Message-ID} of the email the reply answers, or {@code null} if it threads under none
 */
record SentReply(String messageId, String subject, String text, String inReplyTo) {
}
