package org.parasol.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.parasol.ui.PlaywrightTests.RECORD_DIR;

import java.nio.file.Files;

import jakarta.ws.rs.core.Response.Status;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.LoadState;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;

// args MUST stay empty. Quarkus 3.40 builds a test class's reload key from the reflected values of a test-resource
// annotation's attributes (TestResourceManager.prepareTestResourceComparisonInfo), and the JDK annotation proxy
// clones a non-empty array on every read, so the default {"--disable-gpu"} gives each subclass a different key and
// its own application boot. An empty array is not cloned (AnnotationInvocationHandler guards on
// Array.getLength(result) != 0), so all five share one boot. Fixed upstream by quarkusio/quarkus#57464
// (PR #57465, 4.0.0.Beta2); the earlier #56443 fix was incomplete because it did not override toString(). Queued
// for a 3.40 backport - restore the flag once we are on a Quarkus that has it.
@WithPlaywright(args = {}, recordVideoDir = RECORD_DIR, slowMo = 500)
abstract class PlaywrightTests {
	protected static final String RECORD_DIR = "target/playwright";

	@InjectPlaywright
	protected BrowserContext context;

	@ConfigProperty(name = "quarkus.http.test-port")
	int quarkusPort;

	protected String getUrl(String subPage) {
		return "http://localhost:%d/%s".formatted(this.quarkusPort, subPage);
	}

	protected Page loadPage(String subPage, String testName) {
		var page = newPage(testName);
		var response = page.navigate(getUrl(subPage));

		assertThat(response)
			.isNotNull()
			.extracting(Response::status)
			.isEqualTo(Status.OK.getStatusCode());

		page.waitForLoadState(LoadState.NETWORKIDLE);

		return page;
	}

	/**
	 * A blank page whose video is saved as {@code <testName>.webm} when it closes, for a page outside the app (Roundcube).
	 */
	protected Page newPage(String testName) {
		var page = this.context.newPage();
		page.onClose(p -> saveVideoWithReadableName(p, testName));

		return page;
	}

	private void saveVideoWithReadableName(Page page, String testName) {
		var video = page.video();
		var path = video.path();

		if (Files.isRegularFile(path)) {
			path = path.getParent();
		}

		var saveAsPath = path.resolve("%s.webm".formatted(testName));

		// Save the video under a different filename
		video.saveAs(saveAsPath);

		// Delete the randomly generated one
		video.delete();
	}
}
