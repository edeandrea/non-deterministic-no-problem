package org.parasol.intake.mailbox;

/**
 * An email the watcher has moved from the INBOX to the processing folder and now hands over for intake. It's fired as a
 * synchronous CDI event, on the watcher's thread, so a sender's emails reach their observers in the order they arrived.
 *
 * @param email The email
 */
public record EmailReceived(InboundEmail email) {
}
