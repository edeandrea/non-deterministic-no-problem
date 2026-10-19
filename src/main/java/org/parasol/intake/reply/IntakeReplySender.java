package org.parasol.intake.reply;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ForkJoinPool;
import java.util.LinkedHashSet;
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
	 * Sends a plain-text reply about a claim, for a reply that isn't one of the {@link IntakeTemplates} (the AI-written
	 * status answer). Blocks until the mail server accepts it, for up to {@code quarkus.mailer.timeout}.
	 *
	 * @param recipient The customer's address
	 * @param subject The inbound email's subject. A leading {@code Re:} is dropped, so it isn't doubled
	 * @param body The reply text
	 * @param claimNumber The claim the reply is about. It prefixes the subject as {@code [CLM…] Re: }
	 * @param threadingHeaders The headers that thread the reply under the email it answers
	 */
	public void sendTextReply(String recipient, String subject, String body, String claimNumber, ThreadingHeaders threadingHeaders) {
		sendText(recipient, subjectPrefix(Optional.of(claimNumber)) + normalizeSubject(subject), body, threadingHeaders);
	}

	/**
	 * Sends a plain-text reply that isn't about a claim. Blocks until the mail server accepts it, for up to
	 * {@code quarkus.mailer.timeout}.
	 *
	 * @param recipient The customer's address
	 * @param subject The inbound email's subject. A leading {@code Re:} is dropped, so it isn't doubled
	 * @param body The reply text
	 * @param threadingHeaders The headers that thread the reply under the email it answers
	 */
	public void sendTextReply(String recipient, String subject, String body, ThreadingHeaders threadingHeaders) {
		sendText(recipient, subjectPrefix(Optional.empty()) + normalizeSubject(subject), body, threadingHeaders);
	}

	/**
	 * Sends one of the {@link IntakeTemplates} replies about a claim, as HTML and text. Blocks until the mail server
	 * accepts it, for up to {@code quarkus.mailer.timeout}.
	 *
	 * @param template The reply, from {@link IntakeTemplates}
	 * @param recipient The customer's address
	 * @param subject The inbound email's subject. A leading {@code Re:} is dropped, so it isn't doubled
	 * @param claimNumber The claim the reply is about. It prefixes the subject as {@code [CLM…] Re: }
	 * @param threadingHeaders The headers that thread the reply under the email it answers
	 */
	public void sendTemplateReply(MailTemplateInstance template, String recipient, String subject, String claimNumber, ThreadingHeaders threadingHeaders) {
		sendTemplate(template, recipient, subjectPrefix(Optional.of(claimNumber)) + normalizeSubject(subject), threadingHeaders);
	}

	/**
	 * Sends one of the {@link IntakeTemplates} replies that must not disclose a claim ({@link IntakeTemplates#noMatchingClaim},
	 * {@link IntakeTemplates#whichClaim}, not-a-claim, the problem and policy replies), so the subject has no claim-number
	 * prefix. Blocks until the mail server accepts it, for up to {@code quarkus.mailer.timeout}.
	 *
	 * @param template The reply, from {@link IntakeTemplates}
	 * @param recipient The customer's address
	 * @param subject The inbound email's subject. A leading {@code Re:} is dropped, so it isn't doubled
	 * @param threadingHeaders The headers that thread the reply under the email it answers
	 */
	public void sendTemplateReply(MailTemplateInstance template, String recipient, String subject, ThreadingHeaders threadingHeaders) {
		sendTemplate(template, recipient, subjectPrefix(Optional.empty()) + normalizeSubject(subject), threadingHeaders);
	}

	private void sendText(String recipient, String subject, String body, ThreadingHeaders threadingHeaders) {
		var mail = Mail.withText(recipient, subject, body)
			.setFrom(this.config.address())
			.addHeader(AUTO_SUBMITTED, AUTO_REPLIED);

		addThreadingHeaders(mail, threadingHeaders);

		// The blocking Mailer applies quarkus.mailer.timeout itself
		offCallerThread(() -> this.mailer.send(mail));
	}

	private void sendTemplate(MailTemplateInstance template, String recipient, String subject, ThreadingHeaders threadingHeaders) {
		// MailTemplateInstance only sends through the ReactiveMailer, and its sendAndAwait() waits forever (a stalled SMTP
		// server would hang the workflow step). So this awaits the Uni the way the blocking Mailer does: up to
		// quarkus.mailer.timeout, or indefinitely when it's 0
		var send = template
			.mail(baseMail(threadingHeaders))
			.to(recipient)
			.from(this.config.address())
			.subject(subject)
			.send()
			.await();

		offCallerThread(() -> {
			if (this.sendTimeout.isZero()) {
				send.indefinitely();
			}
			else {
				send.atMost(this.sendTimeout);
			}
		});
	}

	// The reactive mailer deadlocks, until the SMTP server's own timeout, when it's blocked on a thread that carries a
	// captured Vert.x context (a ManagedExecutor or Flow task thread, depending on where the run was started). A plain
	// ForkJoinPool thread has none; NotificationService moves its sends there for the same reason. The sender blocks until
	// the mail server accepts the reply either way
	private static void offCallerThread(Runnable send) {
		try {
			CompletableFuture.runAsync(send, ForkJoinPool.commonPool()).join();
		}
		catch (CompletionException e) {
			throw (e.getCause() instanceof RuntimeException cause) ? cause : e;
		}
	}

	private Mail baseMail(ThreadingHeaders threadingHeaders) {
		var mail = new Mail()
			.addHeader(AUTO_SUBMITTED, AUTO_REPLIED);
		addThreadingHeaders(mail, threadingHeaders);
		return mail;
	}

	private void addThreadingHeaders(Mail mail, ThreadingHeaders threadingHeaders) {
		var threadingReferences = new LinkedHashSet<>(threadingHeaders.references());

		threadingHeaders.inReplyTo().ifPresent(messageId -> {
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
