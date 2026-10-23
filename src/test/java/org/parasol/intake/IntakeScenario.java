package org.parasol.intake;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import jakarta.activation.DataHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.parasol.intake.config.IntakeConfig;
import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimCategory;
import org.parasol.claim.model.ClaimCorrespondence;
import org.parasol.claim.model.ClaimImage;
import org.parasol.claim.model.CorrespondenceDirection;
import org.parasol.intake.dispatch.ClaimIntakeDispatcher;
import org.parasol.intake.mailbox.ClaimsMailbox;
import org.parasol.intake.mailbox.InboundEmail;
import org.parasol.intake.mailbox.MailFolder;
import org.parasol.intake.model.ClaimIntakeState;
import org.parasol.intake.review.ReviewDecided;
import org.parasol.intake.review.ReviewDecision;
import org.parasol.intake.review.ReviewDecisionPublisher;
import org.parasol.intake.review.ReviewOutcome;
import org.parasol.intake.workflow.ClaimIntakeFlow;
import org.parasol.testing.mail.GreenMailMailbox;
import org.parasol.testing.mail.ReceivedEmail;

import io.serverlessworkflow.impl.WorkflowInstance;
import io.serverlessworkflow.impl.WorkflowStatus;

/**
 * Drives the intake the way the watcher will: sends a real MIME message to the claims mailbox over SMTP, moves it to the
 * processing folder and submits it. Also reads the replies, finds a claim's waiting run, publishes decisions and cleans up
 * what a test created (everything beyond the six seeded claims, the recorded emails and every Flow run).
 */
@ApplicationScoped
public class IntakeScenario {
	public static final Duration WAIT = Duration.ofSeconds(60);
	private static final int SEEDED_CLAIMS = 6;

	private final ClaimsMailbox mailbox;
	private final GreenMailMailbox greenMail;
	private final ClaimIntakeDispatcher starter;
	private final ClaimIntakeFlow flow;
	private final ReviewDecisionPublisher publisher;
	private final IntakeConfig config;
	private final EntityManager entityManager;
	private final String smtpHost;
	private final int smtpPort;

	IntakeScenario(
		ClaimsMailbox mailbox,
		GreenMailMailbox greenMail,
		ClaimIntakeDispatcher starter,
		ClaimIntakeFlow flow,
		ReviewDecisionPublisher publisher,
		IntakeConfig config,
		EntityManager entityManager,
		@ConfigProperty(name = "quarkus.mailer.host") String smtpHost,
		@ConfigProperty(name = "quarkus.mailer.port") int smtpPort) {

		this.mailbox = mailbox;
		this.greenMail = greenMail;
		this.starter = starter;
		this.flow = flow;
		this.publisher = publisher;
		this.config = config;
		this.entityManager = entityManager;
		this.smtpHost = smtpHost;
		this.smtpPort = smtpPort;
	}

	/**
	 * An email to send to the claims address.
	 */
	public static final class Mail {
		private final String from;
		private String fromName;
		private String subject = "My claim";
		private String body = "Hello";
		private boolean html;
		private final List<String[]> headers = new ArrayList<>();
		private final List<Object[]> attachments = new ArrayList<>();
		private boolean withoutMessageId;
		private String messageId = "<%s@example.com>".formatted(UUID.randomUUID());

		private Mail(String from) {
			this.from = from;
		}

		public static Mail from(String address) {
			return new Mail(address);
		}

		public Mail name(String name) {
			this.fromName = name;
			return this;
		}

		public Mail subject(String subject) {
			this.subject = subject;
			return this;
		}

		public Mail body(String body) {
			this.body = body;
			return this;
		}

		public Mail htmlOnly() {
			this.html = true;
			return this;
		}

		public Mail header(String name, String value) {
			this.headers.add(new String[] { name, value });
			return this;
		}

		public Mail inReplyTo(String messageId) {
			return header("In-Reply-To", messageId).header("References", messageId);
		}

		public Mail attachment(String fileName, String contentType, byte[] data) {
			this.attachments.add(new Object[] { fileName, contentType, data });
			return this;
		}

		public Mail messageId(String messageId) {
			this.messageId = messageId;
			return this;
		}

		public Mail withoutMessageId() {
			this.withoutMessageId = true;
			return this;
		}
	}

	/**
	 * Empties every mailbox and the claims user's folders, and removes what earlier tests left.
	 */
	public void reset() {
		cleanUp();
		this.greenMail.deleteUser(this.config.address());
		this.greenMail.purge();
	}

	/**
	 * Cancels every Flow run and deletes the claims, recorded emails and Flow rows the test created.
	 */
	// The flow's definition is shared: it must not be closed
	@SuppressWarnings("resource")
	public void cleanUp() {
		runIds().forEach(id -> this.flow.definition().activeInstance(id).ifPresent(WorkflowInstance::cancel));
		deleteCreatedData();
	}

	@SuppressWarnings("unchecked")
	@Transactional(TxType.REQUIRES_NEW)
	List<String> runIds() {
		return this.entityManager
			.createNativeQuery("select instance_id from workflow_instance_entity where workflow_name = ?1")
			.setParameter(1, ClaimIntakeFlow.NAME)
			.getResultList();
	}

	@Transactional(TxType.REQUIRES_NEW)
	void deleteCreatedData() {
		ClaimCorrespondence.deleteAll();
		ClaimIntakeState.deleteAll();
		Claim.delete("id > ?1", (long) SEEDED_CLAIMS);
	}

	/**
	 * Sends the email, moves it to the processing folder and submits it, as the watcher will.
	 *
	 * @return The email's {@code Message-ID}, or empty for {@link Mail#withoutMessageId}
	 */
	public String submit(Mail mail) {
		var email = deliverToProcessing(mail);
		this.starter.submit(email);

		return email.messageId().orElse("");
	}

	/**
	 * Sends the email to the claims address. GreenMail has delivered it when the send returns, so a watcher may already have
	 * moved it.
	 *
	 * @return The email's {@code Message-ID}
	 */
	public String deliver(Mail mail) {
		try {
			var message = toMessage(mail);
			message.saveChanges();
			message.setHeader("Message-ID", mail.messageId);
			sendOverSmtp(message);

			return mail.messageId;
		}
		catch (MessagingException | java.io.UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Sends the email and moves it to the processing folder, without submitting it.
	 */
	public InboundEmail deliverToProcessing(Mail mail) {
		try {
			var message = toMessage(mail);
			message.saveChanges();

			if (mail.withoutMessageId) {
				message.removeHeader("Message-ID");
			}
			else {
				message.setHeader("Message-ID", mail.messageId);
			}

			sendOverSmtp(message);
			var email = awaitInboxEmail(mail);

			if (mail.withoutMessageId) {
				this.mailbox.moveWithoutMessageId(MailFolder.INBOX, MailFolder.PROCESSING);
			}
			else {
				this.mailbox.move(email.messageId().orElseThrow(), MailFolder.INBOX, MailFolder.PROCESSING);
			}

			return email;
		}
		catch (MessagingException | java.io.UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}

	private InboundEmail awaitInboxEmail(Mail mail) {
		return await()
			.atMost(WAIT)
			.until(this.mailbox::unprocessed, emails -> emails.stream().anyMatch(email -> matches(email, mail)))
			.stream()
			.filter(email -> matches(email, mail))
			.findFirst()
			.orElseThrow();
	}

	private static boolean matches(InboundEmail email, Mail mail) {
		return mail.withoutMessageId ? email.messageId().isEmpty() && email.subject().equals(mail.subject) : email.messageId().filter(mail.messageId::equals).isPresent();
	}

	private void awaitIn(MailFolder folder, String messageId) {
		await()
			.atMost(WAIT)
			.until(() -> this.mailbox.find(folder, messageId), Optional::isPresent);
	}

	public void awaitFiled(String messageId) {
		awaitIn(MailFolder.PROCESSED, messageId);
	}

	public void awaitFailed(String messageId) {
		awaitIn(MailFolder.FAILED, messageId);
	}

	public List<InboundEmail> inFolder(MailFolder folder) {
		return this.mailbox.list(folder);
	}

	/**
	 * Waits for the replies an address receives, then returns them oldest first. Waits for at least {@code count}, then a
	 * little longer so an unexpected extra reply shows up in the result.
	 */
	public List<ReceivedEmail> awaitReplies(String address, int count) {
		await()
			.atMost(WAIT)
			.until(() -> this.greenMail.messages(address), messages -> messages.size() >= count);

		await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(3)).until(() -> true);

		return this.greenMail.messages(address);
	}

	public List<ReceivedEmail> replies(String address) {
		return this.greenMail.messages(address);
	}

	/**
	 * The run waiting for the claim's review.
	 */
	public WorkflowInstance awaitWaitingRun(long claimId) {
		return await()
			.atMost(WAIT)
			.until(() -> waitingRun(claimId), Optional::isPresent)
			.orElseThrow();
	}

	@SuppressWarnings("resource")
	public Optional<WorkflowInstance> waitingRun(long claimId) {
		return intake(claimId)
			.map(state -> state.reviewRunId)
			.flatMap(id -> this.flow.definition().activeInstance(id))
			.filter(instance -> instance.status() == WorkflowStatus.WAITING);
	}

	/**
	 * Claims the review for the run, as the review endpoint will: {@code reviewRunId} moves to {@code decidingRunId}.
	 */
	@Transactional(TxType.REQUIRES_NEW)
	public String claimReview(long claimId) {
		var state = ClaimIntakeState.findForClaim(claimId).orElseThrow();
		state.decidingRunId = state.reviewRunId;
		state.reviewRunId = null;

		return state.decidingRunId;
	}

	/**
	 * Publishes the decision until the run leaves {@code WAITING}: a decision published the moment the run says it's waiting
	 * can be lost, and the run consumes only one.
	 */
	public void publishUntilWoken(WorkflowInstance run, ReviewDecided decided) {
		var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		var isWoken = false;

		while (!isWoken && (System.nanoTime() < deadline)) {
			this.publisher.publish(decided);
			isWoken = awaitLeavesWaiting(run);
		}

		if (!isWoken) {
			throw new AssertionError("The decision never woke " + run.id());
		}
	}

	private static boolean awaitLeavesWaiting(WorkflowInstance run) {
		var deadline = System.nanoTime() + Duration.ofMillis(250).toNanos();
		var isWoken = false;

		while (!isWoken && (System.nanoTime() < deadline)) {
			isWoken = (run.status() != WorkflowStatus.WAITING);

			if (!isWoken) {
				await().pollDelay(Duration.ofMillis(10)).atMost(Duration.ofMillis(50)).until(() -> true);
			}
		}

		return isWoken;
	}

	public void publish(ReviewDecided decided) {
		this.publisher.publish(decided);
	}

	/**
	 * How many claims have intake state: only a claim the intake created or changed does.
	 */
	@Transactional(TxType.REQUIRES_NEW)
	public long intakeStateCount() {
		return ClaimIntakeState.count();
	}

	/**
	 * How many Flow task rows a run has left.
	 */
	@Transactional(TxType.REQUIRES_NEW)
	public long taskRows(String runId) {
		return ((Number) this.entityManager
			.createNativeQuery("select count(*) from task_info_entity where workflow_instance_id = ?1")
			.setParameter(1, runId)
			.getSingleResult()).longValue();
	}

	/**
	 * How many Flow instance rows a run has left.
	 */
	@Transactional(TxType.REQUIRES_NEW)
	public long instanceRows(String runId) {
		return ((Number) this.entityManager
			.createNativeQuery("select count(*) from workflow_instance_entity where instance_id = ?1")
			.setParameter(1, runId)
			.getSingleResult()).longValue();
	}

	@Transactional(TxType.REQUIRES_NEW)
	public long taskRows() {
		return ((Number) this.entityManager
			.createNativeQuery("select count(*) from task_info_entity")
			.getSingleResult()).longValue();
	}

	/**
	 * The ids of every persisted Flow run that quarkus-flow generated for an agentic system: every workflow but the intake's own.
	 */
	@SuppressWarnings("unchecked")
	@Transactional(TxType.REQUIRES_NEW)
	public Set<String> agenticRunIds() {
		return Set.copyOf(this.entityManager
			.createNativeQuery("select instance_id from workflow_instance_entity where workflow_name <> ?1")
			.setParameter(1, ClaimIntakeFlow.NAME)
			.getResultList());
	}

	/**
	 * How many claims there are; the six seeded ones when nothing a test created is left.
	 */
	@Transactional(TxType.REQUIRES_NEW)
	public long claimCount() {
		return Claim.count();
	}

	/**
	 * How many claims an address has.
	 */
	@Transactional(TxType.REQUIRES_NEW)
	public long claimCount(String address) {
		return Claim.count("emailAddress", address);
	}

	/**
	 * A claim's images, oldest first, without their bytes.
	 */
	@Transactional(TxType.REQUIRES_NEW)
	public List<ClaimImage> images(long claimId) {
		return ClaimImage.listForClaim(claimId);
	}

	public void decide(long claimId, ReviewOutcome outcome, java.util.Set<MissingItem> items) {
		var run = awaitWaitingRun(claimId);
		var runId = claimReview(claimId);
		publishUntilWoken(run, new ReviewDecided(claimId, runId, new ReviewDecision(outcome, items)));
	}

	@Transactional(TxType.REQUIRES_NEW)
	public Claim claim(long claimId) {
		return Claim.findById(claimId);
	}

	/**
	 * The intake's bookkeeping about a claim, if it has any. Its requested items are loaded.
	 */
	@Transactional(TxType.REQUIRES_NEW)
	public Optional<ClaimIntakeState> intake(long claimId) {
		return ClaimIntakeState.findForClaim(claimId);
	}

	/**
	 * Gives a claim intake state, as if the intake had created it.
	 */
	@Transactional(TxType.REQUIRES_NEW)
	public void createIntakeState(long claimId, String conversationId) {
		ClaimIntakeState.createFor(Claim.findById(claimId), conversationId);
	}

	/**
	 * Creates a claim whose intake state has every field set, to show what claim retrieval must not return or load.
	 *
	 * @return The claim's id
	 */
	@Transactional(TxType.REQUIRES_NEW)
	public long createClaimWithFullIntakeState() {
		var claim = new Claim();
		claim.category = ClaimCategory.OTHER;
		claim.policyNumber = "AC-123456789";
		claim.clientName = "Jane Doe";
		claim.emailAddress = "jane.doe@example.com";
		claim.status = "Pending Information";
		claim.subject = "A claim";
		claim.body = "Body";
		claim.persistAndFlush();

		var state = ClaimIntakeState.createFor(claim, "conversation-secret");
		state.reviewRunId = "run-secret-review";
		state.decidingRunId = "run-secret-deciding";
		state.policyNumberGenerated = true;
		state.requestedItems.add(MissingItem.LOCATION);

		return claim.id;
	}

	/**
	 * Records an inbound email against a claim, as the intake does when it accepts one.
	 */
	@Transactional(TxType.REQUIRES_NEW)
	public void recordCorrespondence(String messageId, long claimId) {
		var correspondence = new ClaimCorrespondence();
		correspondence.messageId = messageId;
		correspondence.claim = Claim.findById(claimId);
		correspondence.direction = CorrespondenceDirection.INBOUND;
		correspondence.sentDate = Instant.EPOCH;
		correspondence.persist();
	}

	/**
	 * The claim a customer's email created: the newest claim of that address.
	 */
	public Claim awaitClaimOf(String address) {
		return await()
			.atMost(WAIT)
			.until(() -> newestClaimOf(address), Optional::isPresent)
			.orElseThrow();
	}

	@Transactional(TxType.REQUIRES_NEW)
	Optional<Claim> newestClaimOf(String address) {
		return Claim.<Claim>find("lower(emailAddress) = ?1 order by id desc", address.toLowerCase()).firstResultOptional();
	}

	@Transactional(TxType.REQUIRES_NEW)
	public long instanceRows() {
		return ((Number) this.entityManager
			.createNativeQuery("select count(*) from workflow_instance_entity where workflow_name = ?1")
			.setParameter(1, ClaimIntakeFlow.NAME)
			.getSingleResult()).longValue();
	}

	private MimeMessage toMessage(Mail mail) throws MessagingException, java.io.UnsupportedEncodingException {
		var message = new MimeMessage(Session.getInstance(new Properties()));
		message.setFrom((mail.fromName == null) ? new InternetAddress(mail.from) : new InternetAddress(mail.from, mail.fromName));
		message.setRecipient(RecipientType.TO, new InternetAddress(this.config.address()));
		message.setSubject(mail.subject);
		message.setSentDate(Date.from(Instant.now()));

		for (var header : mail.headers) {
			message.setHeader(header[0], header[1]);
		}

		if (mail.attachments.isEmpty()) {
			if (mail.html) {
				message.setContent(mail.body, "text/html; charset=UTF-8");
			}
			else {
				message.setText(mail.body, "UTF-8");
			}
		}
		else {
			var mixed = new MimeMultipart("mixed");
			var text = new MimeBodyPart();
			text.setText(mail.body, "UTF-8");
			mixed.addBodyPart(text);

			for (var attachment : mail.attachments) {
				var part = new MimeBodyPart();
				part.setDataHandler(new DataHandler(new ByteArrayDataSource((byte[]) attachment[2], (String) attachment[1])));
				part.setFileName((String) attachment[0]);
				part.setDisposition(MimeBodyPart.ATTACHMENT);
				mixed.addBodyPart(part);
			}

			message.setContent(mixed);
		}

		return message;
	}

	private void sendOverSmtp(MimeMessage message) throws MessagingException {
		var properties = new Properties();
		properties.setProperty("mail.smtp.host", this.smtpHost);
		properties.setProperty("mail.smtp.port", String.valueOf(this.smtpPort));

		try (var transport = Session.getInstance(properties).getTransport("smtp")) {
			transport.connect();
			transport.sendMessage(message, message.getAllRecipients());
		}
	}
}
