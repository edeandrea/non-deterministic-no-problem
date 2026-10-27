package org.parasol.intake.dev;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import org.parasol.intake.config.IntakeConfig;

import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.logging.Log;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

/**
 * Dev mode only: mails two claims to the claims address at startup so the review screens have something to show. The real
 * intake does the rest (the claims inbox watcher, the agents, the review wait), so this needs the same model as any other
 * intake email. The first email is complete and ends up {@code Pending Review}; the second says nothing about when or where,
 * so it ends up {@code Pending Information}. GreenMail keeps mail in memory, so they are sent again on every start.
 * <p>
 *   The bean exists only when the build property {@code parasol.dev-seed.enabled} is {@code true} ({@code %dev} in
 *   {@code application.yml}, and the Ollama Maven profiles' {@code quarkus:dev} configuration, since they run dev under the
 *   {@code prod} config profile), and it still does nothing outside {@link LaunchMode#DEVELOPMENT}.
 * </p>
 */
@ApplicationScoped
@IfBuildProperty(name = "parasol.dev-seed.enabled", stringValue = "true")
class DevClaimEmailSeeder {
	private static final List<DevEmail> EMAILS = List.of(
		new DevEmail(
			"dev.pending-review@example.com",
			"Dev Pending-Review",
			"Car hit a lamp post",
			"""
			Hello,

			On October 7, 2026, at about 3:30 PM, I lost control of my car on Elm Street in Springfield and hit a lamp post.
			I was alone in the car and nobody was hurt, but the front bumper and the right headlight are badly damaged.

			My policy number is AC-1234567.

			Thank you,
			Dev Pending-Review
			"""),
		new DevEmail(
			"dev.pending-information@example.com",
			"Dev Pending-Information",
			"Someone damaged my car",
			"""
			Hello,

			Someone damaged my car and I would like to file a claim.

			Thank you,
			Dev Pending-Information
			"""));

	private final ReactiveMailer mailer;
	private final IntakeConfig config;

	DevClaimEmailSeeder(ReactiveMailer mailer, IntakeConfig config) {
		this.mailer = mailer;
		this.config = config;
	}

	void onStart(@Observes StartupEvent event) {
		if ((LaunchMode.current() == LaunchMode.DEVELOPMENT) && this.config.enabled()) {
			// Sent one after the other, without blocking startup
			Multi.createFrom().iterable(EMAILS)
				.onItem().transformToUniAndConcatenate(this::send)
				.collect().asList()
				.subscribe()
				.with(
					sent -> Log.infof("Dev mode: sent %d sample claim emails to %s", sent.size(), this.config.address()),
					e -> Log.warnf(e, "Dev mode: couldn't send the sample claim emails to %s", this.config.address()));
		}
	}

	private Uni<Void> send(DevEmail email) {
		return this.mailer.send(
			Mail.withText(this.config.address(), email.subject(), email.body())
				.setFrom("%s <%s>".formatted(email.name(), email.address())));
	}

	private record DevEmail(String address, String name, String subject, String body) {
	}
}
