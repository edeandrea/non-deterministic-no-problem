package org.parasol.intake.reply;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.parasol.intake.IntakeConfig;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MailTemplate.MailTemplateInstance;
import io.quarkus.mailer.Mailer;

/**
 * Sends intake replies from the claims inbox, with threading headers and claim-number subject prefixes.
 * <p>
 *   Both methods block until the mail server has accepted the reply. The callers are the intake workflow's steps,
 *   which run on worker threads and must know the reply went out before the step moves on (e.g. before filing the email).
 * </p>
 */
@ApplicationScoped
public class IntakeReplySender {
	private static final String AUTO_SUBMITTED = "Auto-Submitted";
	private static final String AUTO_REPLIED = "auto-replied";
	private static final String IN_REPLY_TO = "In-Reply-To";
	private static final String REFERENCES = "References";

	private final IntakeConfig config;
	private final Mailer mailer;
	private final Duration sendTimeout;

	// quarkus.mailer.timeout is the blocking Mailer's own timeout, so templated replies give up at the same point as text
	// replies. It's a single Quarkus-owned key, so a @ConfigProperty rather than a mapping of our own
	IntakeReplySender(IntakeConfig config, Mailer mailer, @ConfigProperty(name = "quarkus.mailer.timeout") Duration sendTimeout) {
		this.config = config;
		this.mailer = mailer;
		this.sendTimeout = sendTimeout;
	}

	/**
	 * Sends a plain-text reply, for a reply that isn't one of the {@link IntakeTemplates} (the AI-written status answer).
	 * Blocks until the mail server accepts it, for up to {@code quarkus.mailer.timeout}.
	 *
	 * @param recipient The customer's address
	 * @param subject The inbound email's subject. A leading {@code Re:} is dropped, so it isn't doubled
	 * @param body The reply text
	 * @param claimNumber The claim the reply is about, if any. It prefixes the subject as {@code [CLM…] Re: }
	 * @param inReplyTo The inbound email's {@code Message-ID}, if it has one
	 * @param references The inbound email's own {@code References}, oldest first
	 */
	public void sendTextReply(
		String recipient,
		String subject,
		String body,
		Optional<String> claimNumber,
		Optional<String> inReplyTo,
		List<String> references) {

		var mail = Mail.withText(recipient, subjectPrefix(claimNumber) + normalizeSubject(subject), body)
			.setFrom(this.config.address())
			.addHeader(AUTO_SUBMITTED, AUTO_REPLIED);

		addThreadingHeaders(mail, inReplyTo, references);

		// The blocking Mailer applies quarkus.mailer.timeout itself
		this.mailer.send(mail);
	}

	/**
	 * Sends one of the {@link IntakeTemplates} replies, as HTML and text. Blocks until the mail server accepts it, for up
	 * to {@code quarkus.mailer.timeout}.
	 *
	 * @param template The reply, from {@link IntakeTemplates}
	 * @param recipient The customer's address
	 * @param subject The inbound email's subject. A leading {@code Re:} is dropped, so it isn't doubled
	 * @param claimNumber The claim the reply is about, if any. It prefixes the subject as {@code [CLM…] Re: }. Leave it
	 *   empty for {@link IntakeTemplates#noMatchingClaim}, which must not disclose a claim
	 * @param inReplyTo The inbound email's {@code Message-ID}, if it has one
	 * @param references The inbound email's own {@code References}, oldest first
	 */
	public void sendTemplateReply(
		MailTemplateInstance template,
		String recipient,
		String subject,
		Optional<String> claimNumber,
		Optional<String> inReplyTo,
		List<String> references) {

		var mail = baseMail(inReplyTo, references);

		// MailTemplateInstance only sends through the ReactiveMailer, and its sendAndAwait() waits forever (a stalled SMTP
		// server would hang the workflow step). So this awaits the Uni the way the blocking Mailer does: up to
		// quarkus.mailer.timeout, or indefinitely when it's 0
		var send = template
			.mail(mail)
			.to(recipient)
			.from(this.config.address())
			.subject(subjectPrefix(claimNumber) + normalizeSubject(subject))
			.send()
			.await();

		if (this.sendTimeout.isZero()) {
			send.indefinitely();
		}
		else {
			send.atMost(this.sendTimeout);
		}
	}

	private Mail baseMail(Optional<String> inReplyTo, List<String> references) {
		var mail = new Mail()
			.addHeader(AUTO_SUBMITTED, AUTO_REPLIED);
		addThreadingHeaders(mail, inReplyTo, references);
		return mail;
	}

	private void addThreadingHeaders(Mail mail, Optional<String> inReplyTo, List<String> references) {
		var threadingReferences = new LinkedHashSet<>(references);

		inReplyTo.ifPresent(messageId -> {
			mail.addHeader(IN_REPLY_TO, messageId);
			threadingReferences.add(messageId);
		});

		if (!threadingReferences.isEmpty()) {
			mail.addHeader(REFERENCES, String.join(" ", threadingReferences));
		}
	}

	private static String normalizeSubject(String subject) {
		return Optional.ofNullable(subject)
			.orElse("")
			.strip()
			.replaceFirst("(?i)^re:\\s*", "");
	}

	private static String subjectPrefix(Optional<String> claimNumber) {
		return claimNumber.map("[%s] Re: "::formatted).orElse("Re: ");
	}
}
