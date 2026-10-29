package org.parasol.testing.mail;

import java.io.IOException;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;

/**
 * Test access to the Toxiproxy container started by {@link ToxiproxyTestResource}, which injects it into fields annotated with {@link InjectToxiproxy},
 * which sits between a client and GreenMail's
 * IMAP port so a test can break the connection. WireMock can't: it only speaks HTTP, and IMAP is a raw TCP stream.
 * <p>
 *   The proxy forwards to GreenMail's IMAP port through the host; {@link #host()} and {@link #imapPort()} are where a
 *   client connects.
 * </p>
 */
public class Toxiproxy {
	private static final String PROXY = "greenmail-imap";
	private static final String SILENT = "silent";

	private final ToxiproxyClient client;
	private final String host;
	private final int imapPort;
	private final String upstream;

	Toxiproxy(ToxiproxyClient client, String host, int imapPort, String upstream) {
		this.client = client;
		this.host = host;
		this.imapPort = imapPort;
		this.upstream = upstream;
	}

	public String host() {
		return this.host;
	}

	/**
	 * @return The host port that forwards to GreenMail's IMAP port
	 */
	public int imapPort() {
		return this.imapPort;
	}

	/**
	 * Puts the proxy back as a clean, enabled pass-through, creating it if needed.
	 */
	public void reset() {
		run(() -> {
			var existing = this.client.getProxyOrNull(PROXY);

			if (existing != null) {
				existing.delete();
			}

			this.client.createProxy(PROXY, "0.0.0.0:" + ToxiproxyTestResource.LISTEN_PORT, this.upstream);
		});
	}

	/**
	 * Stops the proxy: open connections are dropped and new ones are refused.
	 */
	public void disable() {
		run(() -> proxy().disable());
	}

	public void enable() {
		run(() -> proxy().enable());
	}

	/**
	 * Keeps connections open, but delivers nothing from GreenMail to the client (existing and new connections alike).
	 */
	public void goSilent() {
		run(() -> proxy().toxics().timeout(SILENT, ToxicDirection.DOWNSTREAM, 0));
	}

	public void speak() {
		run(() -> proxy().toxics().get(SILENT).remove());
	}

	private Proxy proxy() throws IOException {
		return this.client.getProxy(PROXY);
	}

	private static void run(ToxiproxyAction action) {
		try {
			action.run();
		}
		catch (IOException e) {
			throw new IllegalStateException("Toxiproxy call failed", e);
		}
	}

	@FunctionalInterface
	private interface ToxiproxyAction {
		void run() throws IOException;
	}
}
