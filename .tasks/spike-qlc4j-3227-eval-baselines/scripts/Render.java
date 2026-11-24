///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.microsoft.playwright:playwright:1.57.0
// usage: Render <text file> <png> <title>
import com.microsoft.playwright.*;
import java.nio.file.*;

public class Render {
	public static void main(String... a) throws Exception {
		var text = Files.readString(Path.of(a[0])).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		var html = """
			<html><body style="margin:0;background:#1e1e1e"><div style="font:12px Menlo,monospace;color:#9cdcfe;padding:10px 14px">%s</div>
			<pre style="margin:0;padding:0 14px 14px;font:12px/1.45 Menlo,monospace;color:#d4d4d4;white-space:pre-wrap;width:1360px">%s</pre></body></html>
			""".formatted(a[2], text);
		try (var pw = Playwright.create(); var b = pw.chromium().launch()) {
			var p = b.newPage(new Browser.NewPageOptions().setViewportSize(1400, 600));
			p.setContent(html);
			p.screenshot(new Page.ScreenshotOptions().setPath(Path.of(a[1])).setFullPage(true));
		}
	}
}
