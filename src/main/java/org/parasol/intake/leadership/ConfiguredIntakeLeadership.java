package org.parasol.intake.leadership;

import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;

import org.parasol.intake.config.IntakeConfig;

import io.fabric8.kubernetes.client.KubernetesClient;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

/**
 * The application's {@link IntakeLeadership}, as {@code parasol.intake.leadership.mode} says: always the leader ({@code SINGLE}, which
 * never touches the Kubernetes API), or whoever holds the Lease ({@code KUBERNETES_LEASE}).
 * <p>
 *   It observes shutdown after the inbox watcher has stopped (a lower priority is notified first), so the Lease is released only
 *   once the leader has stopped taking in email.
 * </p>
 */
@ApplicationScoped
class ConfiguredIntakeLeadership implements IntakeLeadership {
	private static final int AFTER_WATCHER = 4000;

	private final IntakeConfig config;
	private final Event<LeadershipAcquired> acquired;
	private final Event<LeadershipLost> lost;
	private final Instance<KubernetesClient> client;
	private volatile KubernetesLeaseLeadership lease;

	ConfiguredIntakeLeadership(IntakeConfig config, Event<LeadershipAcquired> acquired, Event<LeadershipLost> lost, Instance<KubernetesClient> client) {
		this.config = config;
		this.acquired = acquired;
		this.lost = lost;
		this.client = client;
	}

	@Override
	public boolean isLeader() {
		return switch (this.config.leadership().mode()) {
			case SINGLE -> true;
			case KUBERNETES_LEASE -> Optional.ofNullable(this.lease).map(KubernetesLeaseLeadership::isLeader).orElse(false);
		};
	}

	void onStart(@Observes StartupEvent event) {
		if (this.config.enabled()) {
			switch (this.config.leadership().mode()) {
				case SINGLE -> this.acquired.fire(new LeadershipAcquired());
				case KUBERNETES_LEASE -> startCompetingForLease();
			}
		}
	}

	private void startCompetingForLease() {
		var leadership = this.config.leadership();
		var kubernetes = this.client.get();
		var settings = new KubernetesLeaseLeadership.Settings(
			kubernetes.getNamespace(), leadership.leaseName(), identity(leadership), leadership.leaseDuration(), leadership.renewDeadline(), leadership.retryPeriod()
		);

		this.lease = new KubernetesLeaseLeadership(
			kubernetes,
			settings,
			() -> this.acquired.fire(new LeadershipAcquired()),
			() -> this.lost.fire(new LeadershipLost()),
			() -> Quarkus.asyncExit(1)
		);
		this.lease.start();
	}

	// Two instances with one identity would both think they hold the Lease, so there is no fixed fallback
	private static String identity(IntakeConfig.Leadership leadership) {
		return leadership.identity()
		                 .filter(identity -> !identity.isBlank())
		                 .or(() -> Optional.ofNullable(System.getenv("HOSTNAME")).filter(host -> !host.isBlank()))
		                 .orElseGet(() -> UUID.randomUUID().toString());
	}

	void onShutdown(@Observes @Priority(AFTER_WATCHER) ShutdownEvent event) {
		Optional.ofNullable(this.lease)
		        .ifPresent(lease -> {
			        Log.info("Releasing the intake leadership");
			        lease.close();
		        });
	}
}
