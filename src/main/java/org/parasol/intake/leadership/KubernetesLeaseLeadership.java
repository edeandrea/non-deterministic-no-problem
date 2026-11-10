package org.parasol.intake.leadership;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderCallbacks;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderElectionConfigBuilder;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderElector;
import io.fabric8.kubernetes.client.extended.leaderelection.resourcelock.LeaseLock;

import io.quarkus.logging.Log;

/**
 * Competes for a Kubernetes {@code Lease} with fabric8's {@link LeaderElector}. Plain Java, with no CDI, so it's tested against the
 * mock API server without booting the application.
 * <p>
 *   Losing the Lease runs {@code lost} and then {@code exit}: an instance that lost leadership but kept its runs in memory would
 *   compete with the runs the new leader reclaims, so it ends the process and comes back as a follower. fabric8 also calls
 *   {@code onStopLeading} when the elector is canceled, so a stop this class caused (see {@link #close()}) skips both.
 * </p>
 */
final class KubernetesLeaseLeadership implements IntakeLeadership, AutoCloseable {
	/**
	 * Where and how the instance competes.
	 *
	 * @param namespace the namespace of the Lease
	 * @param leaseName the name of the Lease
	 * @param identity what this instance writes as the Lease's holder
	 * @param leaseDuration how long a holder that stops renewing keeps the Lease
	 * @param renewDeadline how long the leader keeps trying to renew
	 * @param retryPeriod how often an instance tries to take or renew
	 */
	record Settings(String namespace, String leaseName, String identity, Duration leaseDuration, Duration renewDeadline, Duration retryPeriod) {
	}

	private final KubernetesClient client;
	private final Settings settings;
	private final Runnable acquired;
	private final Runnable lost;
	private final Runnable exit;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	private volatile boolean closing;
	private volatile boolean leader;
	private volatile CompletableFuture<?> election;

	KubernetesLeaseLeadership(KubernetesClient client, Settings settings, Runnable acquired, Runnable lost, Runnable exit) {
		this.client = client;
		this.settings = settings;
		this.acquired = acquired;
		this.lost = lost;
		this.exit = exit;
	}

	/**
	 * Starts competing and returns at once.
	 */
	void start() {
		var config = new LeaderElectionConfigBuilder()
			.withName(this.settings.leaseName())
			.withLock(new LeaseLock(this.settings.namespace(), this.settings.leaseName(), this.settings.identity()))
			.withLeaseDuration(this.settings.leaseDuration())
			.withRenewDeadline(this.settings.renewDeadline())
			.withRetryPeriod(this.settings.retryPeriod())
			.withReleaseOnCancel(true)
			.withLeaderCallbacks(new LeaderCallbacks(this::onStartLeading, this::onStopLeading, this::onNewLeader))
			.build();

		this.election = new LeaderElector(this.client, config, this.executor).start();
		Log.infof("%s is competing for the Lease %s/%s", this.settings.identity(), this.settings.namespace(), this.settings.leaseName());
	}

	@Override
	public boolean isLeader() {
		return this.leader;
	}

	/**
	 * Stops competing and, if this instance leads, releases the Lease so that another takes it at once. Runs neither {@code lost} nor
	 * {@code exit}.
	 */
	@Override
	public void close() {
		this.closing = true;

		var current = this.election;

		if (current != null) {
			current.cancel(true);
		}

		this.leader = false;
		this.executor.shutdown();
	}

	private void onStartLeading() {
		this.leader = true;
		Log.infof("%s is the intake leader", this.settings.identity());
		this.acquired.run();
	}

	private void onStopLeading() {
		this.leader = false;

		if (!this.closing) {
			Log.errorf("%s lost the Lease %s: it's no longer the intake leader", this.settings.identity(), this.settings.leaseName());

			try {
				this.lost.run();
			}
			finally {
				this.exit.run();
			}
		}
	}

	private void onNewLeader(String holder) {
		Log.debugf("The intake leader is %s", holder);
	}
}
