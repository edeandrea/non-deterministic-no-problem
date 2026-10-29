package org.parasol.intake.mailbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.stream.IntStream;

import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.mail.Folder;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.parasol.intake.config.IntakeConfig;
import org.parasol.intake.IntakeScenario;
import org.parasol.intake.IntakeScenario.Mail;
import org.parasol.intake.mailbox.ClaimsInboxWatcher.Settings;
import org.parasol.testing.StubbedModelsTestProfile;
import org.parasol.testing.mail.InjectToxiproxy;
import org.parasol.testing.mail.Toxiproxy;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduler;
import io.quarkus.virtual.threads.VirtualThreads;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.virtual.ShouldNotPin;
import io.quarkus.test.junit.virtual.VirtualThreadUnit;

// Each test builds its own watcher with short timings and a mocked CDI event. @InjectMock disables the observers declared on the
// mocked bean (quarkusio/quarkus#11122), so a mocked ClaimIntakeDispatcher would never see an event.
// Network faults come from Toxiproxy, which the watcher's IDLE connection (and only that) goes through.
@QuarkusTest
@TestProfile(StubbedModelsTestProfile.class)
// Pinning is only asserted by the tests that log no stack trace: formatting one loads classes under a JDK monitor, which pins
// for microseconds and is no concern of the watcher's, but it makes the tests that log a lost connection flaky
@VirtualThreadUnit
class ClaimsInboxWatcherTests {
	private static final Duration WAIT = Duration.ofSeconds(30);
	private static final Duration QUIET = Duration.ofSeconds(2);
	private static final String SENDER = "jane.doe@example.com";
	// Mail that lands just before an IDLE starts can go unannounced (inherent to IDLE), and the refresh's rescan is what picks it
	// up, so it's short enough for that to cost seconds, and long enough not to matter to the other tests
	private static final Duration LONG_REFRESH = Duration.ofSeconds(5);
	private static final Duration SHORT_DELAY = Duration.ofMillis(100);

	@Inject
	ClaimsMailbox mailbox;

	@Inject
	Scheduler scheduler;

	@Inject
	@VirtualThreads
	ExecutorService virtualThreads;

	@Inject
	IntakeScenario scenario;

	@Inject
	IntakeConfig config;

	@InjectToxiproxy
	Toxiproxy toxiproxy;

	@ConfigProperty(name = "quarkus.mailer.host")
	String smtpHost;

	@ConfigProperty(name = "quarkus.mailer.port")
	int smtpPort;

	private final List<ClaimsInboxWatcher> watchers = new ArrayList<>();

	@SuppressWarnings("unchecked")
	private final Event<EmailReceived> received = mock(Event.class);

	@BeforeEach
	void reset() {
		this.scenario.reset();
		this.toxiproxy.reset();
	}

	@AfterEach
	void stopWatchers() {
		this.watchers.forEach(ClaimsInboxWatcher::stop);
	}

	@Test
	@ShouldNotPin
	void messageDeliveredWhileWatchingIsHandedOver() {
		startWatcher(LONG_REFRESH);

		var messageId = deliver("Delivered while watching");

		assertThat(handedOver(1))
			.singleElement()
			.returns(messageId, email -> email.messageId().orElseThrow())
			.returns("Delivered while watching", InboundEmail::subject);
		assertThat(inFolder(MailFolder.PROCESSING)).containsExactly(messageId);
		assertThat(inFolder(MailFolder.INBOX)).isEmpty();
	}

	@Test
	@ShouldNotPin
	void backlogIsHandedOverInArrivalOrder() {
		var messageIds = IntStream.range(0, 4)
			.mapToObj(i -> deliver("Backlog " + i))
			.toList();

		startWatcher(LONG_REFRESH);

		assertThat(handedOver(4))
			.extracting(email -> email.messageId().orElseThrow())
			.containsExactlyElementsOf(messageIds);
	}

	@Test
	@ShouldNotPin
	void emailsAreHandedOverWithoutWaitingForTheirRuns() {
		startWatcher(LONG_REFRESH);

		// Nothing here ever ends a run: no observer starts one, and the watcher still hands the whole backlog over
		var messageIds = IntStream.range(0, 5)
			.mapToObj(i -> deliver("Without waiting " + i))
			.toList();

		assertThat(handedOver(5))
			.extracting(email -> email.messageId().orElseThrow())
			.containsExactlyElementsOf(messageIds);
		assertThat(inFolder(MailFolder.PROCESSING)).containsExactlyElementsOf(messageIds);
	}

	@Test
	void exceptionFromTheObserverDoesNotStopTheNextEmail() {
		doThrow(new IllegalStateException("Boom"))
			.doNothing()
			.when(this.received)
			.fire(any());
		var watcher = startWatcher(LONG_REFRESH);

		var first = deliver("Observer fails");
		var second = deliver("Observer works");

		assertThat(handedOver(2))
			.extracting(email -> email.messageId().orElseThrow())
			.containsExactly(first, second);
		assertThat(watcher.isRunning()).isTrue();
	}

	@Test
	@ShouldNotPin
	void emailIsInProcessingWhenHandedOverAndNeverHandedOverTwice() {
		var states = new CopyOnWriteArrayList<String>();
		doAnswer(invocation -> {
			var id = invocation.<EmailReceived>getArgument(0).email().messageId().orElseThrow();
			states.add("processing=%s inbox=%s".formatted(this.mailbox.find(MailFolder.PROCESSING, id).isPresent(), this.mailbox.find(MailFolder.INBOX, id).isPresent()));

			return null;
		})
			.when(this.received)
			.fire(any());
		startWatcher(Duration.ofMillis(500));

		deliver("Once only");
		handedOver(1);

		// Several IDLE refreshes pass
		await()
			.during(QUIET)
			.atMost(WAIT)
			.untilAsserted(() -> verify(this.received, times(1)).fire(any()));
		assertThat(states).containsExactly("processing=true inbox=false");
	}

	@Test
	void emailsLeftInProcessingAreHandedOverInPlaceBeforeTheBacklog() {
		var left = this.scenario.deliverToProcessing(Mail.from(SENDER).subject("Left behind"))
			.messageId()
			.orElseThrow();
		var newer = deliver("Newer");

		startWatcher(LONG_REFRESH);

		assertThat(handedOver(2))
			.extracting(email -> email.messageId().orElseThrow())
			.containsExactly(left, newer);
		assertThat(inFolder(MailFolder.PROCESSING)).containsExactly(left, newer);
	}

	@Test
	void processingFolderIsHandedOverOnlyOnceAcrossReconnects() throws Exception {
		var left = this.scenario.deliverToProcessing(Mail.from(SENDER).subject("Left behind"))
			.messageId()
			.orElseThrow();
		startWatcher(this.toxiproxy.host(), this.toxiproxy.imapPort(), LONG_REFRESH, SHORT_DELAY);
		handedOver(1);

		this.toxiproxy.disable();
		this.toxiproxy.enable();
		var after = deliver("After the drop");

		assertThat(handedOver(2))
			.extracting(email -> email.messageId().orElseThrow())
			.containsExactly(left, after);
	}

	@Test
	void unreadableEmailGoesToFailedAndTheNextOneIsStillProcessed() throws Exception {
		sendWithoutBoundary();
		var good = deliver("Readable");
		startWatcher(LONG_REFRESH);

		assertThat(handedOver(1))
			.extracting(email -> email.messageId().orElseThrow())
			.containsExactly(good);
		assertThat(countIn(this.config.folders().failed())).isEqualTo(1);
		assertThat(countIn("INBOX")).isZero();
	}

	@Test
	void watcherReconnectsAfterTheConnectionDrops() {
		startWatcher(this.toxiproxy.host(), this.toxiproxy.imapPort(), LONG_REFRESH, SHORT_DELAY);
		deliver("Before the drop");
		handedOver(1);

		this.toxiproxy.disable();
		var during = deliver("During the outage");
		this.toxiproxy.enable();

		assertThat(handedOver(2))
			.extracting(email -> email.messageId().orElseThrow())
			.contains(during);
	}

	@Test
	void watcherReconnectsAfterTheConnectionGoesSilent() {
		// Read timeout 2 s, refresh 1 s: the refresh's DONE gets no answer
		startWatcher(this.toxiproxy.host(), this.toxiproxy.imapPort(), Duration.ofSeconds(1), SHORT_DELAY);
		deliver("Before going silent");
		handedOver(1);

		this.toxiproxy.goSilent();
		var during = deliver("While silent");

		// Long enough for the read timeout to fire and the watcher to reconnect while nothing gets through
		await().pollDelay(Duration.ofSeconds(4)).until(() -> true);

		this.toxiproxy.speak();

		assertThat(handedOverUntil(during))
			.extracting(email -> email.messageId().orElseThrow())
			.contains(during);
	}

	@Test
	void startReturnsAtOnceWhenTheMailboxIsUnreachableAndTheBacklogIsProcessedOnceItIsBack() {
		this.toxiproxy.disable();
		var messageId = deliver("Waiting out the outage");
		var startedAt = Instant.now();

		var watcher = startWatcher(this.toxiproxy.host(), this.toxiproxy.imapPort(), LONG_REFRESH, SHORT_DELAY);

		assertThat(Duration.between(startedAt, Instant.now())).isLessThan(Duration.ofSeconds(2));
		assertThat(watcher.isRunning()).isTrue();
		verify(this.received, never()).fire(any());

		this.toxiproxy.enable();

		assertThat(handedOver(1))
			.extracting(email -> email.messageId().orElseThrow())
			.containsExactly(messageId);
	}

	@Test
	@ShouldNotPin
	void shutdownStopsWatching() {
		var watcher = startWatcher(LONG_REFRESH);
		deliver("Connected");
		handedOver(1);

		watcher.stop();

		assertThat(watcher.isRunning()).isFalse();
	}

	@Test
	void shutdownStopsWatchingWhileItWaitsToReconnect() {
		this.toxiproxy.disable();
		var watcher = startWatcher(new Settings(this.toxiproxy.host(), this.toxiproxy.imapPort(), LONG_REFRESH, Duration.ofMinutes(5), Duration.ofMinutes(5)));
		var startedAt = Instant.now();

		// Let it fail once, so it's sleeping off its delay
		await().pollDelay(Duration.ofSeconds(1)).until(watcher::isRunning);
		watcher.stop();

		assertThat(watcher.isRunning()).isFalse();
		assertThat(Duration.between(startedAt, Instant.now())).isLessThan(Duration.ofSeconds(10));
	}

	@Test
	void disabledIntakeStartsNoWatching() {
		var disabled = mock(IntakeConfig.class);
		when(disabled.enabled()).thenReturn(false);
		var watcher = new ClaimsInboxWatcher(disabled, this.mailbox, this.received, Settings.from(this.config), this.scheduler, this.virtualThreads);

		watcher.onStart(new StartupEvent());

		assertThat(watcher.isRunning()).isFalse();
	}

	private ClaimsInboxWatcher startWatcher(Duration idleRefresh) {
		return startWatcher(this.config.imap().host(), this.config.imap().port(), idleRefresh, SHORT_DELAY);
	}

	private ClaimsInboxWatcher startWatcher(String host, int port, Duration idleRefresh, Duration reconnectDelay) {
		return startWatcher(new Settings(host, port, idleRefresh, reconnectDelay, Duration.ofSeconds(1)));
	}

	private ClaimsInboxWatcher startWatcher(Settings settings) {
		var watcher = new ClaimsInboxWatcher(this.config, this.mailbox, this.received, settings, this.scheduler, this.virtualThreads);
		this.watchers.add(watcher);
		watcher.start();

		return watcher;
	}

	private String deliver(String subject) {
		return this.scenario.deliver(Mail.from(SENDER).subject(subject));
	}

	private List<InboundEmail> handedOverSoFar() {
		var captor = ArgumentCaptor.forClass(EmailReceived.class);
		verify(this.received, atLeast(0)).fire(captor.capture());

		return captor.getAllValues()
			.stream()
			.map(EmailReceived::email)
			.toList();
	}

	private List<InboundEmail> handedOverUntil(String messageId) {
		await()
			.atMost(WAIT)
			.untilAsserted(() -> assertThat(handedOverSoFar())
				.extracting(email -> email.messageId().orElseThrow())
				.contains(messageId));

		return handedOverSoFar();
	}

	private List<InboundEmail> handedOver(int count) {
		var captor = ArgumentCaptor.forClass(EmailReceived.class);
		verify(this.received, timeout(WAIT.toMillis()).times(count)).fire(captor.capture());

		return captor.getAllValues()
			.stream()
			.map(EmailReceived::email)
			.toList();
	}

	private List<String> inFolder(MailFolder folder) {
		return this.mailbox.list(folder)
			.stream()
			.map(email -> email.messageId().orElseThrow())
			.toList();
	}

	// A multipart message without a boundary can't be parsed. It's sent raw, since Jakarta Mail would add the boundary itself
	private void sendWithoutBoundary() throws MessagingException {
		var message = new MimeMessage(Session.getInstance(new Properties()));
		message.setFrom(new InternetAddress(SENDER));
		message.setRecipient(RecipientType.TO, new InternetAddress(this.config.address()));
		message.setSubject("Unreadable");
		message.setText("Not really multipart");
		message.saveChanges();
		message.setHeader("Content-Type", "multipart/mixed");
		message.setHeader("Message-ID", "<unreadable@example.com>");

		var properties = new Properties();
		properties.setProperty("mail.smtp.host", this.smtpHost);
		properties.setProperty("mail.smtp.port", String.valueOf(this.smtpPort));

		try (var transport = Session.getInstance(properties).getTransport("smtp")) {
			transport.connect();
			transport.sendMessage(message, message.getAllRecipients());
		}
	}

	// Counted over IMAP without parsing, because ClaimsMailbox can't read the unreadable email
	private int countIn(String folderName) throws MessagingException {
		var imap = this.config.imap();

		try (var store = Session.getInstance(new Properties()).getStore("imap")) {
			store.connect(imap.host(), imap.port(), imap.user(), "any-password");
			var folder = store.getFolder(folderName);

			try (folder) {
				folder.open(Folder.READ_ONLY);

				return folder.getMessageCount();
			}
		}
	}
}
