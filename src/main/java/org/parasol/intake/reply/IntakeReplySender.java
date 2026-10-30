package org.parasol.intake.reply;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.parasol.intake.config.IntakeConfig;
import org.parasol.intake.observability.MailClientSpan;
import org.parasol.intake.observability.MailClientSpan.Protocol;
import org.parasol.intake.observability.IntakeActivities;
import org.parasol.intake.observability.IntakeActivity.ReplySent;

import io.opentelemetry.context.Context;
import io.quarkus.logging.Log;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MailTemplate.MailTemplateInstance;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.qute.Variant;
import io.smallrye.mutiny.Uni;

/**
 * Sends intake replies from the claims inbox, with threading headers and claim-number subject prefixes.
 * <p>
 *   The methods block until the mail server has accepted the reply. The callers are the intake workflow's steps,
 *   which run on worker threads and must know the reply went out before the step moves on (e.g. before filing the email).
 * </p>
 * <p>
 *   Every reply is sent with a {@code Message-ID} generated here. A reply about a claim is then recorded as an
 *   {@link org.parasol.claim.model.CorrespondenceDirection#OUTBOUND} {@link org.parasol.claim.model.ClaimCorrespondence}, so
 *   the claim's transcript shows it and a customer reply that names only our {@code Message-ID} still finds the claim.
 * </p>
 */
@ApplicationScoped
public class IntakeReplySender {
	private static final String AUTO_SUBMITTED = "Auto-Submitted";
	private static final String AUTO_REPLIED = "auto-replied";
	private static final String IN_REPLY_TO = "In-Reply-To";
	private static final String REFERENCES = "References";
	private static final String MESSAGE_ID = "Message-ID";
	private static final String STATUS_ANSWER = "status_answer";

	private final IntakeConfig config;
	private final ReactiveMailer mailer;
	private final Duration sendTimeout;
	private final IntakeActivities activities;
	private final ReplyRecorder recorder;
	private final String messageIdDomain;

	// Every reply gives up at quarkus.mailer.timeout, the blocking Mailer's own timeout. It's a single Quarkus-owned key, so
	// a @ConfigProperty rather than a mapping of our own
	IntakeReplySender(IntakeConfig config, ReactiveMailer mailer, @ConfigProperty(name = "quarkus.mailer.timeout") Duration sendTimeout, IntakeActivities activities, ReplyRecorder recorder) {
		this.activities = activities;
		this.recorder = recorder;
		// The config can't change while the app runs, so the domain is worked out once. A Message-ID's right-hand side is a domain
		this.messageIdDomain = config.address().substring(config.address().indexOf('@') + 1);
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
		var messageId = newMessageId();
		var fullSubject = subjectPrefix(Optional.of(claimNumber)) + normalizeSubject(subject);

		sendText(recipient, fullSubject, body, messageId, threadingHeaders);
		record(claimNumber, () -> new SentReply(messageId, fullSubject, body));
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
		sendText(recipient, subjectPrefix(Optional.empty()) + normalizeSubject(subject), body, newMessageId(), threadingHeaders);
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
		var messageId = newMessageId();
		var fullSubject = subjectPrefix(Optional.of(claimNumber)) + normalizeSubject(subject);

		sendTemplate(template, recipient, fullSubject, messageId, threadingHeaders);
		record(claimNumber, () -> new SentReply(messageId, fullSubject, textOf(template)));
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
		sendTemplate(template, recipient, subjectPrefix(Optional.empty()) + normalizeSubject(subject), newMessageId(), threadingHeaders);
	}

	// The template's id is IntakeTemplates/<method>, optionally with a variant suffix
	private static String templateName(MailTemplateInstance template) {
		var id = template.templateInstance().getTemplate().getId();
		var name = id.substring(id.lastIndexOf('/') + 1);
		var variant = name.indexOf('.');

		return (variant < 0) ? name : name.substring(0, variant);
	}

	private void sendText(String recipient, String subject, String body, String messageId, ThreadingHeaders threadingHeaders) {
		var mail = Mail.withText(recipient, subject, body)
			.setFrom(this.config.address())
			.addHeader(AUTO_SUBMITTED, AUTO_REPLIED)
			.addHeader(MESSAGE_ID, messageId);

		addThreadingHeaders(mail, threadingHeaders);

		deliver(this.mailer.send(mail), STATUS_ANSWER);
	}

	private void sendTemplate(MailTemplateInstance template, String recipient, String subject, String messageId, ThreadingHeaders threadingHeaders) {
		deliver(template
			.mail(baseMail(messageId, threadingHeaders))
			.to(recipient)
			.from(this.config.address())
			.subject(subject)
			.send(), templateName(template));
	}

	// The one place that talks to the mail server, so the smtp send span (and claim.intake.mail.client.duration) covers only the
	// send, not the recording of the reply that follows. Not private, so the interceptor applies to the calls from this class
	@MailClientSpan(protocol = Protocol.SMTP, operation = "send")
	void deliver(Uni<Void> send, String template) {
		awaitSent(send);
		this.activities.publish(new ReplySent(template));
	}

	// The reply has gone out: failing the step now would answer the customer twice, so a failure to record it only logs. The text
	// is rendered inside the try, so a rendering problem only logs too
	private void record(String claimNumber, Supplier<SentReply> reply) {
		try {
			this.recorder.record(claimNumber, reply.get());
		}
		catch (RuntimeException e) {
			Log.warnf(e, "Sent a reply about claim %s but couldn't record it", claimNumber);
		}
	}

	// The text/plain variant, which is what the mailer sends as the text part of the template
	private static String textOf(MailTemplateInstance template) {
		var instance = template.templateInstance();
		var variant = ((List<?>) instance.getAttribute(TemplateInstance.VARIANTS)).stream()
			.map(Variant.class::cast)
			.filter(candidate -> candidate.getContentType().equals(Variant.TEXT_PLAIN))
			.findFirst()
			.orElseThrow();

		return instance.setAttribute(TemplateInstance.SELECTED_VARIANT, variant)
			.render();
	}

	private String newMessageId() {
		return "<%s@%s>".formatted(UUID.randomUUID(), this.messageIdDomain);
	}

	// Waits up to quarkus.mailer.timeout, or indefinitely when it's 0, the way the blocking Mailer does. (A template's
	// sendAndAwait() waits forever, so a stalled SMTP server would hang the workflow step.) The wait is off the caller's
	// thread, see below
	private void awaitSent(Uni<Void> send) {
		var await = send.await();

		try {
			offCallerThread(() -> {
				if (this.sendTimeout.isZero()) {
					await.indefinitely();
				}
				else {
					await.atMost(this.sendTimeout);
				}
			});
		}
		catch (RuntimeException e) {
			throw new ReplyFailedException(e);
		}
	}

	// The reactive mailer deadlocks, until the SMTP server's own timeout, when it's blocked on a thread that carries a
	// captured Vert.x context (a ManagedExecutor or Flow task thread, depending on where the run was started). A new virtual
	// thread has none, and unlike a ForkJoinPool thread it inherits the caller's context class loader, which a template's
	// {config:...} lookup (ConfigProvider.getConfig()) needs in dev mode. The sender blocks until the mail server accepts
	// the reply either way. The OTel context goes along (it isn't the Vert.x one), so the send stays in the caller's span and trace
	private static void offCallerThread(Runnable send) {
		try {
			CompletableFuture.runAsync(Context.current().wrap(send), Thread::startVirtualThread).join();
		}
		catch (CompletionException e) {
			throw (e.getCause() instanceof RuntimeException cause) ? cause : e;
		}
	}

	private Mail baseMail(String messageId, ThreadingHeaders threadingHeaders) {
		var mail = new Mail()
			.addHeader(AUTO_SUBMITTED, AUTO_REPLIED)
			.addHeader(MESSAGE_ID, messageId);
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
