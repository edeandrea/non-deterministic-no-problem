package org.parasol.testing.mail;

import static org.awaitility.Awaitility.await;

import java.nio.file.Path;
import java.time.Duration;

import com.microsoft.playwright.FrameLocator;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.assertions.PlaywrightAssertions;

/**
 * Drives the Roundcube webmail from compose-devservices.yml (its {@code webmail} profile, which {@code UiTestProfile} turns on)
 * in a Playwright page, the way a customer would: log in, write or reply to an email, and wait for one to arrive.
 * <p>
 *   The selectors are Roundcube 1.7's elastic skin, found by inspecting the running image (the task 12 notes in the #216
 *   plan list them). The message list shows the newest message first, and a message opens in the preview frame next to it.
 * </p>
 */
public final class Roundcube {
	private static final Duration MAIL_WAIT = Duration.ofSeconds(60);
	private static final LocatorAssertions.HasCountOptions SHORT_WAIT = new LocatorAssertions.HasCountOptions().setTimeout(2_000);

	private final Page page;
	private final String baseUrl;

	private Roundcube(Page page, String baseUrl) {
		this.page = page;
		this.baseUrl = baseUrl;
	}

	/**
	 * Opens Roundcube in the page and logs in. Auth is off in GreenMail, so any password does.
	 *
	 * @param page The page to drive
	 * @param baseUrl Roundcube's address, e.g. {@code http://localhost:<parasol.mail.roundcube-port>}
	 * @param address Who to log in as
	 */
	public static Roundcube loginAs(Page page, String baseUrl, String address) {
		var roundcube = new Roundcube(page, baseUrl);
		page.navigate(baseUrl);

		// The browser context, and so Roundcube's session cookie, outlives a test: log out of an earlier test's session first
		if (page.locator("#rcmloginuser").count() == 0) {
			page.locator("#taskmenu a.logout").click();
		}

		page.locator("#rcmloginuser").fill(address);
		page.locator("#rcmloginpwd").fill("any");
		page.locator("#rcmloginsubmit").click();
		PlaywrightAssertions.assertThat(page.locator("#messagelist")).isAttached();

		return roundcube;
	}

	/**
	 * Writes a new email and sends it.
	 *
	 * @param attachment A file to attach, or {@code null} for none
	 */
	public void send(String to, String subject, String body, Path attachment) {
		this.page.navigate(this.baseUrl + "/?_task=mail&_action=compose");
		this.page.locator("#compose_to input[type=text]").fill(to);
		this.page.locator("#compose-subject").fill(subject);
		this.page.locator("#composebody").fill(body);

		if (attachment != null) {
			this.page.locator("#uploadformInput").setInputFiles(attachment);
			PlaywrightAssertions.assertThat(this.page.locator("#attachment-list li:not(.uploading)"))
				.containsText(attachment.getFileName().toString());
		}

		clickSend();
	}

	/**
	 * Waits until the inbox holds {@code count} messages whose subject contains {@code subject}.
	 */
	public void awaitMessages(String subject, int count) {
		await()
			.atMost(MAIL_WAIT)
			.ignoreExceptions()
			.untilAsserted(() -> {
				this.page.navigate(this.baseUrl + "/?_task=mail&_mbox=INBOX");
				PlaywrightAssertions.assertThat(messages(subject)).hasCount(count, SHORT_WAIT);
			});
	}

	/**
	 * Opens the newest message whose subject contains {@code subject}, and returns its body. Roundcube shows a message's HTML
	 * part when it has one, so a list in an intake reply is {@code li} elements, not {@code - } lines.
	 */
	public Locator openNewest(String subject) {
		messages(subject).first().click();

		return preview().locator("#messagebody");
	}

	/**
	 * Replies to the open message (see {@link #openNewest}), writing {@code body} above the quoted message.
	 */
	public void reply(String body) {
		this.page.locator("#toolbar-menu a.reply").click();
		var message = this.page.locator("#composebody");
		PlaywrightAssertions.assertThat(message).not().isEmpty();
		message.fill("%s%n%n%s".formatted(body, message.inputValue()));
		clickSend();
	}

	private FrameLocator preview() {
		return this.page.frameLocator("#messagecontframe");
	}

	private Locator messages(String subject) {
		return this.page.locator("#messagelist tbody tr").filter(new Locator.FilterOptions().setHasText(subject));
	}

	// Roundcube goes back to the mailbox once the message is sent
	private void clickSend() {
		this.page.locator("button.btn.send").click();
		this.page.waitForURL(url -> !url.contains("_action=compose"));
	}
}