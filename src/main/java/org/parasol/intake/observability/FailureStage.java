package org.parasol.intake.observability;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Where a run failed, as a bounded label: the {@code stage} tag of {@code claim.intake.failures}. A stage is recognized by the
 * package or class of an exception in the failure's cause chain. The mailbox and the reply sender throw their own exceptions
 * ({@code MailboxMoveException}, {@code ReplyFailedException}) so the stage names the operation that failed, not what the library
 * underneath threw. {@link #MOVE} is declared before {@link #FETCH}, which claims the rest of the mailbox package.
 */
public enum FailureStage {
	MOVE("org.parasol.intake.mailbox.MailboxMoveException"),
	FETCH("org.parasol.intake.mailbox", "org.parasol.intake.workflow.mail", "org.parasol.intake.dispatch.ProcessingEmailMissingException"),
	AGENT("dev.langchain4j", "org.parasol.intake.agent"),
	PERSIST("jakarta.persistence", "jakarta.transaction", "org.hibernate"),
	REPLY("org.parasol.intake.reply.ReplyFailedException", "jakarta.mail", "io.quarkus.mailer"),
	UNKNOWN();

	private final String[] packages;

	FailureStage(String... packages) {
		this.packages = packages;
	}

	/**
	 * Finds the stage of a failure: the first exception in its cause chain from a package a stage knows.
	 *
	 * @param failure What failed
	 * @return The stage, {@link #UNKNOWN} if no exception in the chain is from a known package
	 */
	public static FailureStage of(Throwable failure) {
		return Stream.iterate(failure, Objects::nonNull, Throwable::getCause)
			.map(cause -> cause.getClass().getName())
			.flatMap(name -> Arrays.stream(values()).filter(stage -> stage.owns(name)))
			.findFirst()
			.orElse(UNKNOWN);
	}

	String tag() {
		return name().toLowerCase(Locale.ROOT);
	}

	private boolean owns(String className) {
		return Arrays.stream(this.packages).anyMatch(className::startsWith);
	}
}
