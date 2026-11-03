package org.parasol.testing.mail;

import java.util.Map;

import org.eclipse.microprofile.config.ConfigProvider;

import eu.rekawek.toxiproxy.ToxiproxyClient;
import org.testcontainers.containers.ToxiproxyContainer;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager.TestInjector.AnnotatedAndMatchesType;

/**
 * Starts a Toxiproxy container for the tests that need to break a TCP connection, and injects a {@link Toxiproxy} for it into the
 * test fields annotated with {@link InjectToxiproxy}. It's registered by {@code StubbedModelsTestProfile}, so it lives and dies with that profile's one application boot.
 * <p>
 *   GreenMail is a Compose Dev Service, so there is no shared container network to put Toxiproxy on: it reaches GreenMail
 *   through the host (a Compose-mapped port), as {@link #HOST_ALIAS}.
 * </p>
 * <p>
 *   To revisit when quarkus-network-faults supports IMAP: https://github.com/quarkiverse/quarkus-network-faults/issues/14
 * </p>
 */
public class ToxiproxyTestResource implements QuarkusTestResourceLifecycleManager {
	static final String HOST_ALIAS = "host.testcontainers.internal";

	// The first of the ports that ToxiproxyContainer exposes for proxies
	static final int LISTEN_PORT = 8666;

	private static final String IMAGE = "ghcr.io/shopify/toxiproxy:2.13.0";

	private ToxiproxyContainer container;
	private Toxiproxy toxiproxy;

	@Override
	public Map<String, String> start() {
		this.container = new ToxiproxyContainer(IMAGE)
			.withExtraHost(HOST_ALIAS, "host-gateway");

		this.container.start();

		return Map.of();
	}

	@Override
	public void inject(TestInjector testInjector) {
		if (this.toxiproxy == null) {
			// GreenMail's port is only known once the application is up, so it's read here rather than in start()
			var imapPort = ConfigProvider.getConfig().getValue("parasol.mail.imap-port", Integer.class);

			this.toxiproxy = new Toxiproxy(
				new ToxiproxyClient(this.container.getHost(), this.container.getControlPort()),
				this.container.getHost(),
				this.container.getMappedPort(LISTEN_PORT),
				HOST_ALIAS + ":" + imapPort
			);
		}

		testInjector.injectIntoFields(this.toxiproxy, new AnnotatedAndMatchesType(InjectToxiproxy.class, Toxiproxy.class));
	}

	@Override
	public void stop() {
		if (this.container != null) {
			this.container.stop();
		}
	}
}
