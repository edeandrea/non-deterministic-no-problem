///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.microsoft.playwright:playwright:1.57.0
// usage: Shot <baseUrl> <email> <password> <outDir> <step...>
// step = "goto:<path>" | "shot:<name>" | "click:<text>" | "clicksel:<css>" | "wait:<ms>" | "dump:<name>"
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import java.nio.file.*;

public class Shot {
	public static void main(String... a) throws Exception {
		var base = a[0];
		var out = Path.of(a[3]);
		Files.createDirectories(out);
		try (var pw = Playwright.create(); var browser = pw.chromium().launch()) {
			var ctx = browser.newContext(new Browser.NewContextOptions().setViewportSize(1600, 1000));
			var page = ctx.newPage();
			page.navigate(base + "/auth/sign-in");
			page.fill("input[name=email]", a[1]);
			page.fill("input[type=password]", a[2]);
			page.click("button[type=submit]");
			page.waitForURL(u -> !u.contains("/auth/"), new Page.WaitForURLOptions().setTimeout(30000));
			for (int i = 4; i < a.length; i++) {
				var step = a[i];
				var arg = step.substring(step.indexOf(':') + 1);
				System.out.println("step " + step);
				switch (step.substring(0, step.indexOf(':'))) {
					case "goto" -> {
						page.navigate(base + arg);
						page.waitForLoadState(LoadState.NETWORKIDLE);
					}
					case "shot" -> page.screenshot(new Page.ScreenshotOptions().setPath(out.resolve(arg + ".png")).setFullPage(true));
					case "click" -> page.getByText(arg, new Page.GetByTextOptions().setExact(true)).first().click();
					case "clicksel" -> page.locator(arg).first().click();
					case "wait" -> page.waitForTimeout(Double.parseDouble(arg));
					case "dump" -> Files.writeString(out.resolve(arg + ".txt"), page.url() + "\n" + page.locator("body").innerText());
					default -> throw new IllegalArgumentException(step);
				}
			}
		}
	}
}
