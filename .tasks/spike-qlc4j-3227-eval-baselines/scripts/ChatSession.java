///usr/bin/env jbang "$0" "$@" ; exit $?
// One chat session per run: connect to the "chat" route, ask one question, print the answer, close (ends the chat scope).
// args: claimId inceptionDate question  (claim summary is read from stdin)
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public class ChatSession {
	public static void main(String... args) throws Exception {
		var claimId = args[0];
		var inceptionDate = args[1];
		var question = args[2];
		var claim = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
		var chatId = "spike-" + System.nanoTime();
		var answer = new CompletableFuture<String>();
		var connected = new CompletableFuture<String>();
		var buffer = new StringBuilder();

		var ws = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create("ws://localhost:8080/_chat/routes"), new WebSocket.Listener() {
			@Override
			public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
				buffer.append(data);
				if (last) {
					var msg = buffer.toString();
					buffer.setLength(0);
					System.err.println("<< " + msg);
					if (msg.contains("\"type\":\"Completed\"") && !connected.isDone()) {
						connected.complete(field(msg, "data"));
					}
					else if (msg.contains("\"type\":\"Message\"")) {
						answer.complete(field(msg, "data"));
					}
					else if (msg.contains("\"type\":\"Error\"") || msg.contains("\"type\":\"Failed\"")) {
						answer.completeExceptionally(new IllegalStateException(msg));
					}
				}
				webSocket.request(1);
				return null;
			}
		}).get(10, TimeUnit.SECONDS);

		ws.sendText("{\"type\":\"Connect\",\"chatId\":\"%s\",\"route\":\"chat\"}".formatted(chatId), true).join();
		var scope = connected.get(30, TimeUnit.SECONDS);
		var data = "{\"query\":{\"claimId\":%s,\"inceptionDate\":%s,\"query\":%s,\"claim\":%s}}".formatted(claimId, json(inceptionDate), json(question), json(claim));
		ws.sendText("{\"type\":\"ChatRouteMessage\",\"chatId\":\"%s\",\"scopeId\":%s,\"data\":%s}".formatted(chatId, json(scope), data), true).join();
		System.out.println(answer.get(300, TimeUnit.SECONDS));
		ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(10, TimeUnit.SECONDS);
		Thread.sleep(500);
	}

	static String json(String s) {
		var sb = new StringBuilder("\"");
		for (char c : s.toCharArray()) {
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> sb.append(c);
			}
		}
		return sb.append('"').toString();
	}

	// crude extraction of a top-level string field (good enough for a spike)
	static String field(String msg, String name) {
		var key = "\"" + name + "\":";
		var i = msg.indexOf(key);
		if (i < 0) {
			return "";
		}
		i += key.length();
		if (msg.charAt(i) != '"') {
			return msg.substring(i);
		}
		var sb = new StringBuilder();
		for (int j = i + 1; j < msg.length(); j++) {
			char c = msg.charAt(j);
			if (c == '\\') {
				char n = msg.charAt(++j);
				sb.append(switch (n) { case 'n' -> '\n'; case 't' -> '\t'; case 'r' -> '\r'; default -> n; });
			}
			else if (c == '"') {
				break;
			}
			else {
				sb.append(c);
			}
		}
		return sb.toString();
	}
}
