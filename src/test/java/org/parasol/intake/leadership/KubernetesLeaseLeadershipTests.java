package org.parasol.intake.leadership;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.parasol.intake.leadership.KubernetesLeaseLeadership.Settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;

// Plain JUnit against fabric8's mock API server in CRUD mode, which rejects a stale resourceVersion with a 409 like a real one.
// No Quarkus boot, so it adds no application start (and no test resource to split a group's reload key)
@EnableKubernetesMockClient(crud = true)
class KubernetesLeaseLeadershipTests {
	private static final Duration LEASE = Duration.ofSeconds(3);
	private static final Duration WAIT = Duration.ofSeconds(20);

	KubernetesClient client;

	private final List<KubernetesLeaseLeadership> started = new CopyOnWriteArrayList<>();
	private final List<String> events = new CopyOnWriteArrayList<>();

	@AfterEach
	void stopAll() {
		this.started.forEach(KubernetesLeaseLeadership::close);
	}

	@Test
	void exactlyOneOfTwoLeadsAndACleanStopHandsOver() {
		var exits = new AtomicInteger();
		var first = start("first", exits);
		var second = start("second", exits);

		await().atMost(WAIT).until(() -> first.isLeader() || second.isLeader());
		assertThat(first.isLeader() && second.isLeader()).isFalse();

		var leader = first.isLeader() ? first : second;
		var other = first.isLeader() ? second : first;

		leader.close();

		await().atMost(WAIT).until(other::isLeader);
		assertThat(leader.isLeader()).isFalse();
		assertThat(this.events).containsOnlyOnce("acquired:" + (first.isLeader() ? "first" : "second"));
		assertThat(exits).hasValue(0);
		assertThat(this.events).noneMatch(event -> event.startsWith("lost"));
	}

	@Test
	void aLeaderThatCantRenewLosesTheLeaseAndTheExitHookRunsAfterTheLostEvent() {
		var exits = new AtomicInteger();
		var leader = start("leader", exits);

		await().atMost(WAIT).until(leader::isLeader);

		// Another holder takes the Lease over, as a successor would once this one's renewals stopped
		this.client.leases()
		           .inNamespace(this.client.getNamespace())
		           .withName("parasol-intake-leader")
		           .edit(lease -> {
			           lease.getSpec().setHolderIdentity("someone-else");
			           lease.getSpec().setRenewTime(java.time.ZonedDateTime.now().plusMinutes(5));

			           return lease;
		           });

		await().atMost(WAIT).untilAsserted(() -> assertThat(exits).hasValue(1));
		assertThat(leader.isLeader()).isFalse();
		assertThat(this.events).containsSubsequence("acquired:leader", "lost:leader", "exit:leader");
	}

	@Test
	void aFollowerTakesOverWithinTheLeaseDurationOfALeaderThatStoppedRenewing() {
		var exits = new AtomicInteger();
		var crashed = start("crashed", exits);

		await().atMost(WAIT).until(crashed::isLeader);

		// A crash: the holder never releases and never renews again. The Lease stays as it was written
		this.client.leases()
		           .inNamespace(this.client.getNamespace())
		           .withName("parasol-intake-leader")
		           .edit(lease -> {
			           lease.getSpec().setHolderIdentity("dead");
			           lease.getSpec().setRenewTime(java.time.ZonedDateTime.now());

			           return lease;
		           });

		var successor = start("successor", exits);
		var started = System.nanoTime();

		await().atMost(WAIT).until(successor::isLeader);
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(LEASE.plusSeconds(8));
	}

	private KubernetesLeaseLeadership start(String identity, AtomicInteger exits) {
		var settings = new Settings(this.client.getNamespace(), "parasol-intake-leader", identity, LEASE, Duration.ofSeconds(2), Duration.ofMillis(250));
		var leadership = new KubernetesLeaseLeadership(
			this.client,
			settings,
			() -> this.events.add("acquired:" + identity),
			() -> this.events.add("lost:" + identity),
			() -> {
				this.events.add("exit:" + identity);
				exits.incrementAndGet();
			}
		);

		this.started.add(leadership);
		leadership.start();

		return leadership;
	}
}
