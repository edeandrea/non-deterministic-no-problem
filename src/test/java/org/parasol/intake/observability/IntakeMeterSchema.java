package org.parasol.intake.observability;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.parasol.claim.model.IntakeClaimStatus;
import org.parasol.intake.MissingItem;
import org.parasol.intake.mailbox.SkippedAttachment;
import org.parasol.intake.model.EmailType;
import org.parasol.intake.reply.IntakeTemplates;
import org.parasol.intake.review.ReviewOutcome;

import io.quarkus.mailer.MailTemplate.MailTemplateInstance;

/**
 * Every {@code claim.intake.*} meter, with its tag keys and, where bounded, the values each may take. {@code IntakeMetricsTests}
 * checks the registry against it and {@code GrafanaDashboardTests} checks that the intake dashboard charts every meter in it.
 */
final class IntakeMeterSchema {
	private IntakeMeterSchema() {
	}

	/**
	 * One meter: its name and, for each tag key it carries, the values it may take. An empty set means the values are open-ended
	 * (an exception class, a mail operation) and are checked separately.
	 */
	record MeterSchema(String name, Map<String, Set<String>> tags) {
		Set<String> tagKeys() {
			return this.tags.keySet();
		}
	}

	static Map<String, MeterSchema> byName() {
		return all().stream().collect(Collectors.toUnmodifiableMap(MeterSchema::name, Function.identity()));
	}

	private static MeterSchema meter(String name, Map<String, Set<String>> tags) {
		return new MeterSchema(name, tags);
	}

	static List<MeterSchema> all() {
		// Derive template names from their declarations, not a second list that can drift from Qute.
		var templates = Stream.concat(
				Arrays.stream(IntakeTemplates.class.getDeclaredMethods())
					.filter(method -> method.getReturnType().equals(MailTemplateInstance.class))
					.map(Method::getName),
				Stream.of("status_answer"))
			.collect(Collectors.toUnmodifiableSet());
		return List.of(
			meter("claim.intake.emails", Map.of("outcome", Arrays.stream(EmailOutcome.values()).map(EmailOutcome::tag).collect(Collectors.toSet()))),
			meter("claim.intake.claim.status.transitions", Map.of("status", Arrays.stream(IntakeClaimStatus.values()).map(IntakeClaimStatus::label).collect(Collectors.toSet()))),
			meter("claim.intake.replies", Map.of("template", templates)),
			meter("claim.intake.review.decisions", Map.of("decision", Arrays.stream(ReviewOutcome.values())
				.map(outcome -> outcome.name().toLowerCase(Locale.ROOT))
				.collect(Collectors.toSet()))),
			meter("claim.intake.review.wait", Map.<String, Set<String>>of()),
			meter("claim.intake.failures", Map.of("stage", Arrays.stream(FailureStage.values()).map(FailureStage::tag).collect(Collectors.toSet()),
				"outcome", Arrays.stream(FailureOutcome.values()).map(FailureOutcome::tag).collect(Collectors.toSet()), "exception", Set.<String>of())),
			meter("claim.intake.reviews.pending", Map.<String, Set<String>>of()),
			meter("claim.intake.watcher.connected", Map.<String, Set<String>>of()),
			meter("claim.intake.watcher.reconnects", Map.<String, Set<String>>of()),
			meter("claim.intake.email.latency", Map.of("outcome", Arrays.stream(EmailOutcome.values()).map(EmailOutcome::tag).collect(Collectors.toSet()))),
			meter("claim.intake.attachments", Map.of("result", Set.of("kept", "skipped"),
				"reason", Stream.concat(Stream.of("none"), Arrays.stream(SkippedAttachment.Reason.values()).map(reason -> reason.name().toLowerCase(Locale.ROOT))).collect(Collectors.toSet()))),
			meter("claim.intake.email.size", Map.<String, Set<String>>of()),
			meter("claim.intake.classifications", Map.of("email_type", Arrays.stream(EmailType.values()).map(type -> type.name().toLowerCase(Locale.ROOT)).collect(Collectors.toSet()),
				"matched_claim", Arrays.stream(MatchedClaimKind.values()).map(MatchedClaimKind::tag).collect(Collectors.toSet()))),
			meter("claim.intake.matcher.result", Map.of("result", Arrays.stream(MatcherResult.values()).map(MatcherResult::tag).collect(Collectors.toSet()))),
			meter("claim.intake.extraction.completeness", Map.<String, Set<String>>of()),
			meter("claim.intake.missing.items", Map.of("item", Arrays.stream(MissingItem.values()).map(item -> item.name().toLowerCase(Locale.ROOT)).collect(Collectors.toSet()))),
			meter("claim.intake.claim.emails", Map.<String, Set<String>>of()),
			meter("claim.intake.claim.time.to.complete", Map.<String, Set<String>>of()),
			meter("claim.intake.review.overrides", Map.of("agreement", Arrays.stream(ReviewAgreement.values()).map(ReviewAgreement::tag).collect(Collectors.toSet()))),
			meter("claim.intake.mail.client.duration", Map.of("protocol", Set.of("imap", "smtp"), "operation", Set.<String>of(), "outcome", Set.of("success", "error"))),
			meter("claim.intake.mailbox.lock.wait", Map.of("mode", Arrays.stream(MailboxLockMode.values()).map(MailboxLockMode::tag).collect(Collectors.toSet()))),
			meter("claim.intake.inbox.depth", Map.<String, Set<String>>of()),
			meter("claim.intake.review.oldest.pending", Map.<String, Set<String>>of()));
	}
}