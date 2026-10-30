package org.parasol.intake.mailbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import jakarta.mail.Folder;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.parasol.intake.config.IntakeConfig;
import org.parasol.intake.observability.IntakeActivityEvents;
import org.opentest4j.TestAbortedException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smallrye.config.SmallRyeConfigBuilder;

// Opt-in diagnostic: real mailbox methods and GreenMail, without booting unrelated Quarkus Dev Services.
@EnabledIfSystemProperty(named = "parasol.mail.stress", matches = "true")
class ClaimsMailboxConcurrencyTests {
	private static final int WORKERS = 8;
	private static final Duration ROUND_TIMEOUT = Duration.ofSeconds(60);
	private static final Path RESULTS = Path.of(System.getProperty("parasol.mail.stress.results", "target/imap-concurrency"));

	// JUnit owns this resource across parameterized invocations; stopServer releases it once after the class.
	private static GenericContainer<?> server;
	private static final AtomicBoolean hasLeakedWorkers = new AtomicBoolean();
	private static String host;
	private static int imapPort;
	private static int smtpPort;

	@BeforeAll
	static void startServer() throws IOException {
		Files.createDirectories(RESULTS);
		host = System.getProperty("parasol.mail.stress.host", "localhost");
		imapPort = Integer.getInteger("parasol.mail.stress.imap-port", 0);
		smtpPort = Integer.getInteger("parasol.mail.stress.smtp-port", 0);

		if (imapPort == 0) {
			server = new GenericContainer<>("docker.io/greenmail/standalone:2.1.15")
				.withExposedPorts(3025, 3143)
				.waitingFor(Wait.forLogMessage(".*Starting GreenMail API server.*", 1));
			server.start();
			host = server.getHost();
			imapPort = server.getMappedPort(3143);
			smtpPort = server.getMappedPort(3025);
		}

		assertThat(smtpPort).isPositive();
	}

	@AfterAll
	static void stopServer() throws IOException {
		if (server != null) {
			try {
				Files.writeString(RESULTS.resolve("greenmail.log"), server.getLogs());
			}
			finally {
				server.stop();
			}
		}
	}

	static Stream<Arguments> scenarios() {
		var strategies = System.getProperty("parasol.mail.stress.strategies", "CURRENT").split(",");
		var workloads = System.getProperty("parasol.mail.stress.workloads", "READS,MOVES,MIXED,WORKFLOW,OLDEST").split(",");
		var targets = System.getProperty("parasol.mail.stress.precreated", "true,false").split(",");

		return Arrays.stream(strategies)
			.flatMap(strategy -> Arrays.stream(workloads)
				.flatMap(workload -> Arrays.stream(targets)
					.map(target -> Arguments.of(Strategy.valueOf(strategy), Workload.valueOf(workload), Boolean.parseBoolean(target)))));
	}

	@ParameterizedTest(name = "{0} / {1} / precreated={2}")
	@MethodSource("scenarios")
	@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = ThreadMode.SEPARATE_THREAD)
	void concurrentCallsPreserveEveryMessage(Strategy strategy, Workload workload, boolean isPrecreated) throws Exception {
		assumeFalse(hasLeakedWorkers.get(), "Previous workers did not terminate; restart the diagnostic JVM/server");
		var rounds = Integer.getInteger("parasol.mail.stress.rounds", 5);

		assertThat(rounds).isPositive();

		for (var round = 0; round < rounds; round++) {
			runRound(strategy, workload, isPrecreated, round);
		}
	}

	private void runRound(Strategy strategy, Workload workload, boolean isPrecreated, int round) throws Exception {
		var name = "%s-%s-%s-%d".formatted(strategy, workload, isPrecreated, round);
		var address = "%s@stress.example.com".formatted(UUID.randomUUID());
		var config = config(address);
		var mailbox = new ClaimsMailbox(config, IntakeActivityEvents.into(new SimpleMeterRegistry()));
		var gate = instrumentGate(mailbox, strategy);
		var ids = seed(address, isPrecreated);
		assertThat(config.imap().user()).isEqualTo(address);
		verifyFolder(mailbox.list(MailFolder.INBOX), ids, ids);
		var ready = new CountDownLatch(WORKERS);
		var start = new CountDownLatch(1);
		var active = new ConcurrentHashMap<Thread, String>();
		var operations = new ConcurrentHashMap<String, Long>();
		var threads = Thread.ofVirtual()
			.name("imap-stress-%s-".formatted(name), 0)
			.factory();
		// ExecutorService.close waits without a deadline; a stall diagnostic must instead bound shutdown in finally.
		var executor = Executors.newThreadPerTaskExecutor(threads);
		var futures = new ArrayList<Future<Void>>();
		var started = System.nanoTime();
		var primaryFailure = new AtomicReference<Throwable>();

		try {
			for (var worker = 0; worker < WORKERS; worker++) {
				var index = worker;
				Callable<Void> task = () -> {
					ready.countDown();
					assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
					var isReader = (workload == Workload.READS) || ((workload == Workload.MIXED) && ((index % 2) == 0));
					var id = ids.get(index);

					if (workload == Workload.OLDEST) {
						var email = call(gate, false, "oldest-%d".formatted(index), active, operations,
							() -> mailbox.moveOldest(MailFolder.INBOX, MailFolder.PROCESSING));
						assertThat(email).isPresent();
						var moved = email.orElseThrow();
						verifyEmail(moved, ids);
						var movedId = moved.messageId().orElseThrow();
						assertThat(call(gate, false, "file-oldest-%d".formatted(index), active, operations,
							() -> mailbox.move(movedId, MailFolder.PROCESSING, MailFolder.PROCESSED))).isTrue();
					}
					else if (isReader) {
						for (var read = 0; read < Integer.getInteger("parasol.mail.stress.reads", 1); read++) {
							var listed = call(gate, true, "list-%d-%d".formatted(index, read), active, operations, () -> mailbox.list(MailFolder.INBOX));
							var stable = IntStream.range(0, WORKERS)
								.filter(other -> (workload == Workload.READS) || ((other % 2) == 0))
								.mapToObj(ids::get)
								.toList();
							verifyListed(listed, stable, ids);
							verifyFound(call(gate, true, "find-%d-%d".formatted(index, read), active, operations,
								() -> mailbox.find(MailFolder.INBOX, id)), id, ids);
						}
					}
					else {
						if (workload == Workload.WORKFLOW) {
							verifyFound(call(gate, true, "find-inbox-%d".formatted(index), active, operations,
								() -> mailbox.find(MailFolder.INBOX, id)), id, ids);
						}

						assertThat(call(gate, false, "move-processing-%d".formatted(index), active, operations,
							() -> mailbox.move(id, MailFolder.INBOX, MailFolder.PROCESSING))).isTrue();

						if (workload == Workload.WORKFLOW) {
							verifyFound(call(gate, true, "find-processing-%d".formatted(index), active, operations,
								() -> mailbox.find(MailFolder.PROCESSING, id)), id, ids);
						}

						assertThat(call(gate, false, "move-processed-%d".formatted(index), active, operations,
							() -> mailbox.move(id, MailFolder.PROCESSING, MailFolder.PROCESSED))).isTrue();
					}

					return null;
				};
				futures.add(executor.submit(task));
			}

			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			var deadline = System.nanoTime() + ROUND_TIMEOUT.toNanos();

			var failures = new ArrayList<Throwable>();

			for (var future : futures) {
				try {
					future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
				}
				catch (ExecutionException failure) {
					failures.add(failure.getCause());
				}
			}

			if (!failures.isEmpty()) {
				var failure = new AssertionError("%d worker(s) failed".formatted(failures.size()));

				for (var cause : failures) {
					failure.addSuppressed(cause);
				}

				throw failure;
			}

			assertThat(gate.maximum.get())
				.as("The requested concurrency strategy was exercised")
				.isBetween(1, WORKERS);

			var requiresOverlap = (strategy == Strategy.NONE) || (strategy == Strategy.LIMIT_2)
				|| (workload == Workload.READS) || (workload == Workload.MIXED) || (workload == Workload.WORKFLOW);
			var hasExpectedOverlap = (strategy == Strategy.EXCLUSIVE) || !requiresOverlap || (gate.maximum.get() > 1);

			if ((strategy == Strategy.EXCLUSIVE) || !requiresOverlap) {
				assertThat(gate.maximum.get()).isEqualTo(1);
			}
			else if (strategy == Strategy.LIMIT_2) {
				assertThat(gate.maximum.get()).isLessThanOrEqualTo(2);
			}

			var inbox = IntStream.range(0, WORKERS)
				.filter(index -> (workload == Workload.READS) || ((workload == Workload.MIXED) && ((index % 2) == 0)))
				.mapToObj(ids::get)
				.toList();
			var processed = ids.stream()
				.filter(id -> !inbox.contains(id))
				.toList();

			verifyFolder(mailbox.list(MailFolder.INBOX), inbox, ids);
			verifyFolder(mailbox.list(MailFolder.PROCESSED), processed, ids);
			assertThat(mailbox.list(MailFolder.PROCESSING)).isEmpty();
			assertThat(mailbox.list(MailFolder.FAILED)).isEmpty();

			// Scheduling may serialize a legal mixed workload. Do not count that as concurrency evidence or a mailbox failure.
			writeResult(name, hasExpectedOverlap ? "PASS" : "INCONCLUSIVE", started, gate, operations, "");
			assumeTrue(hasExpectedOverlap, "Intended overlap was not observed; repeat this scenario");
		}
		catch (TestAbortedException inconclusive) {
			throw inconclusive;
		}
		catch (Exception | AssertionError failure) {
			primaryFailure.set(failure);
			var trace = new StringWriter();
			failure.printStackTrace(new PrintWriter(trace));
			var diagnostics = new StringBuilder(trace.toString());

			for (var entry : active.entrySet()) {
				diagnostics.append("\n%s %s\n".formatted(entry.getKey(), entry.getValue()));

				for (var frame : entry.getKey().getStackTrace()) {
					diagnostics.append("  %s\n".formatted(frame));
				}
			}

			try {
				writeResult(name, "FAIL", started, gate, operations, diagnostics.toString());
			}
			catch (IOException diagnosticFailure) {
				failure.addSuppressed(diagnosticFailure);
			}

			throw failure;
		}
		finally {
			start.countDown();
			executor.shutdownNow();
			try {
				assertThat(executor.awaitTermination(15, TimeUnit.SECONDS))
					.as("Workers terminate; restart the diagnostic JVM/server if not: %s", RESULTS.resolve(name + ".txt"))
					.isTrue();
			}
			catch (InterruptedException | AssertionError cleanupFailure) {
				hasLeakedWorkers.set(true);

				if (primaryFailure.get() == null) {
					throw cleanupFailure;
				}
				else {
					primaryFailure.get().addSuppressed(cleanupFailure);
				}
			}
		}
	}

	private static void verifyEmail(InboundEmail email, List<String> ids) {
		assertThat(email.messageId()).isPresent();
		var id = email.messageId().orElseThrow();
		var index = ids.indexOf(id);
		assertThat(index).isNotNegative();
		assertThat(email.subject()).isEqualTo("concurrent-%d".formatted(index));
		assertThat(email.body().strip()).isEqualTo("Mailbox payload %d: %s".formatted(index, id));
		assertThat(email.fromAddress()).contains("sender-%d@example.com".formatted(index));
	}

	private static void verifyListed(List<InboundEmail> emails, List<String> stableIds, List<String> ids) {
		assertThat(emails).allSatisfy(email -> verifyEmail(email, ids));
		assertThat(emails)
			.extracting(email -> email.messageId().orElseThrow())
			.doesNotHaveDuplicates()
			.containsAll(stableIds);
	}

	private static void verifyFolder(List<InboundEmail> emails, List<String> expectedIds, List<String> ids) {
		verifyListed(emails, expectedIds, ids);
		assertThat(emails)
			.extracting(email -> email.messageId().orElseThrow())
			.containsExactlyInAnyOrderElementsOf(expectedIds);
	}

	private static void verifyFound(Optional<InboundEmail> found, String id, List<String> ids) {
		assertThat(found).isPresent();
		var email = found.orElseThrow();
		assertThat(email.messageId()).contains(id);
		verifyEmail(email, ids);
	}

	private static <T> T call(ProbeLock gate, boolean isRead, String operation, Map<Thread, String> active,
		Map<String, Long> operations, Supplier<T> action) {
		var thread = Thread.currentThread();
		var started = System.nanoTime();
		active.put(thread, operation);
		gate.isRead.set(isRead);

		try {
			return action.get();
		}
		finally {
			operations.put(operation, System.nanoTime() - started);
			gate.isRead.remove();
			active.remove(thread);
		}
	}

	private static ProbeLock instrumentGate(ClaimsMailbox mailbox, Strategy strategy) throws ReflectiveOperationException {
		// Test-only substitution: use the real method body, parser, connections and close/expunge path unchanged.
		var field = ClaimsMailbox.class.getDeclaredField("folderLock");
		field.setAccessible(true);
		var gate = new ProbeLock(strategy, (ReadWriteLock) field.get(mailbox));
		field.set(mailbox, gate);

		return gate;
	}

	private static IntakeConfig config(String address) {
		return new SmallRyeConfigBuilder()
			.addDefaultInterceptors()
			.withMapping(IntakeConfig.class)
			.withDefaultValues(Map.of(
				"quarkus.mailer.host", host,
				"parasol.intake.address", address,
				"parasol.intake.imap.host", host,
				"parasol.intake.imap.port", String.valueOf(imapPort),
				"parasol.intake.watcher.idle-refresh", "PT1M",
				"parasol.intake.watcher.reconnect-delay", "PT1S",
				"parasol.intake.watcher.max-reconnect-delay", "PT1M"))
			.build()
			.getConfigMapping(IntakeConfig.class);
	}

	private static List<String> seed(String address, boolean isPrecreated) throws Exception {
		var properties = new Properties();
		properties.setProperty("mail.smtp.connectiontimeout", "5000");
		properties.setProperty("mail.smtp.timeout", "5000");
		properties.setProperty("mail.smtp.writetimeout", "5000");
		properties.setProperty("mail.imap.connectiontimeout", "5000");
		properties.setProperty("mail.imap.timeout", "5000");
		var session = Session.getInstance(properties);
		var ids = new ArrayList<String>();

		try (var transport = session.getTransport("smtp")) {
			transport.connect(host, smtpPort, null, null);

			for (var index = 0; index < WORKERS; index++) {
				var id = "<%s@stress.example.com>".formatted(UUID.randomUUID());
				var message = new MimeMessage(session);
				message.setFrom(new InternetAddress("sender-%d@example.com".formatted(index)));
				message.setRecipient(RecipientType.TO, new InternetAddress(address));
				message.setSubject("concurrent-%d".formatted(index));
				message.setText("Mailbox payload %d: %s".formatted(index, id), "UTF-8");
				message.saveChanges();
				message.setHeader("Message-ID", id);
				transport.sendMessage(message, message.getAllRecipients());
				ids.add(id);
			}
		}

		if (isPrecreated) {
			try (var store = session.getStore("imap")) {
				store.connect(host, imapPort, address, "");

				for (var name : List.of("Processing", "Processed", "Failed")) {
					assertThat(store.getFolder(name).create(Folder.HOLDS_MESSAGES)).isTrue();
				}
			}
		}

		return List.copyOf(ids);
	}

	private static void writeResult(String name, String status, long started, ProbeLock gate, Map<String, Long> operations,
		String diagnostics) throws IOException {
		var elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
		var maximum = operations.values().stream()
			.mapToLong(Long::longValue)
			.max()
			.orElse(0) / 1_000_000;
		var summary = "%s\t%s\t%d\t%d\t%d\t%d".formatted(name, status, elapsed, operations.size(), maximum, gate.maximum.get());
		Files.writeString(RESULTS.resolve(name + ".txt"), summary + "\n" + operations + "\n" + diagnostics);
		// Also runnable outside Quarkus augmentation, where the static Log facade is not transformed.
		Logger.getLogger(ClaimsMailboxConcurrencyTests.class)
			.infof("IMAP_STRESS %s", summary);
	}

	private enum Strategy {
		CURRENT,
		EXCLUSIVE,
		NONE,
		READ_WRITE,
		MOVES_ONLY,
		LIMIT_2
	}

	private enum Workload {
		READS,
		MOVES,
		MIXED,
		WORKFLOW,
		OLDEST
	}

	private static class ProbeLock implements Lock, ReadWriteLock {
		private final Strategy strategy;
		private final ReadWriteLock current;
		private final Lock exclusive = new ReentrantLock(true);
		private final ReentrantReadWriteLock readWrite = new ReentrantReadWriteLock(true);
		private final Lock moves = new ReentrantLock(true);
		private final Semaphore permits = new Semaphore(2, true);
		private final ThreadLocal<Boolean> isRead = ThreadLocal.withInitial(() -> false);
		private final AtomicInteger active = new AtomicInteger();
		private final AtomicInteger maximum = new AtomicInteger();

		private ProbeLock(Strategy strategy, ReadWriteLock current) {
			this.strategy = strategy;
			this.current = current;
		}

		@Override
		public void lock() {
			switch (this.strategy) {
				case CURRENT -> selectedCurrentLock().lock();
				case EXCLUSIVE -> this.exclusive.lock();
				case NONE -> { }
				case READ_WRITE -> selectedReadWriteLock().lock();
				case MOVES_ONLY -> {
					if (!this.isRead.get()) {
						this.moves.lock();
					}
				}
				case LIMIT_2 -> this.permits.acquireUninterruptibly();
			}

			this.maximum.accumulateAndGet(this.active.incrementAndGet(), Math::max);
		}

		@Override
		public void unlock() {
			this.active.decrementAndGet();

			switch (this.strategy) {
				case CURRENT -> selectedCurrentLock().unlock();
				case EXCLUSIVE -> this.exclusive.unlock();
				case NONE -> { }
				case READ_WRITE -> selectedReadWriteLock().unlock();
				case MOVES_ONLY -> {
					if (!this.isRead.get()) {
						this.moves.unlock();
					}
				}
				case LIMIT_2 -> this.permits.release();
			}
		}

		@Override
		public Lock readLock() {
			this.isRead.set(true);
			return this;
		}

		@Override
		public Lock writeLock() {
			this.isRead.set(false);
			return this;
		}

		private Lock selectedCurrentLock() {
			return this.isRead.get() ? this.current.readLock() : this.current.writeLock();
		}

		private Lock selectedReadWriteLock() {
			return this.isRead.get() ? this.readWrite.readLock() : this.readWrite.writeLock();
		}

		@Override
		public void lockInterruptibly() {
			throw new UnsupportedOperationException("Only the mailbox's lock/unlock operations are supported by this diagnostic");
		}

		@Override
		public boolean tryLock() {
			throw new UnsupportedOperationException("Only the mailbox's lock/unlock operations are supported by this diagnostic");
		}

		@Override
		public boolean tryLock(long time, TimeUnit unit) {
			throw new UnsupportedOperationException("Only the mailbox's lock/unlock operations are supported by this diagnostic");
		}

		@Override
		public Condition newCondition() {
			throw new UnsupportedOperationException("Only the mailbox's lock/unlock operations are supported by this diagnostic");
		}
	}
}