package org.parasol.intake.mailbox;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Starts and stops the application's own {@link ClaimsInboxWatcher} from a test outside this package. {@code %test} turns
 * the intake off, so the watcher doesn't start at boot; a test that wants mail picked up the way it is in production (the
 * Roundcube end-to-end test) turns it on for its own duration and off again, without a test profile of its own.
 * <p>
 *   This class adds no behavior: it only exists to cross the package boundary. {@link ClaimsInboxWatcher} is a package-private
 *   CDI bean, and so are its {@code start()} and {@code stop()}, so a test in another package (the Playwright tests live in
 *   {@code org.parasol.ui}) can neither inject it nor call them. The alternatives were worse:
 * </p>
 * <ul>
 *   <li>Making the watcher and those methods public would widen production code only for a test, against the project's rule
 *   that CDI beans and their methods stay package-private. Nothing in {@code src/main} needs them public.</li>
 *   <li>Moving the end-to-end test into this package doesn't work: it extends {@code PlaywrightTests}, which is
 *   package-private in {@code org.parasol.ui}, where every Playwright test lives.</li>
 * </ul>
 * <p>
 *   Being test-only and in the watcher's own package, this bridge leaves the watcher's visibility as it is.
 * </p>
 */
@ApplicationScoped
public class InboxWatcherControl {
	private final ClaimsInboxWatcher watcher;

	InboxWatcherControl(ClaimsInboxWatcher watcher) {
		this.watcher = watcher;
	}

	public void start() {
		this.watcher.start();
	}

	public void stop() {
		this.watcher.stop();
	}

	public boolean isRunning() {
		return this.watcher.isRunning();
	}
}