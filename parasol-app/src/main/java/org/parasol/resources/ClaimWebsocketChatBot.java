package org.parasol.resources;

import org.parasol.ai.ClaimService;
import org.parasol.model.claim.ClaimBotQuery;
import org.parasol.model.claim.ClaimBotQueryResponse;

import ai.scoring.conversation.ConversationBoundary;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.annotations.WithSpan;

import io.quarkus.logging.Log;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnError;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;

@WebSocket(path = "/ws/query")
public class ClaimWebsocketChatBot {
    private final ClaimService bot;
		private final WebSocketConnection connection;

    public ClaimWebsocketChatBot(ClaimService bot, WebSocketConnection connection) {
        this.bot = bot;
	      this.connection = connection;
    }

    @OnOpen
    public void onOpen() {
        Log.infof("Websocket connection %s opened", connection.id());
    }

    @OnClose
    public void onClose() {
        Log.infof("Websocket connection %s closed", connection.id());
    }

    @OnError
    public ClaimBotQueryResponse onError(Throwable error) {
        var message = "Error occurred during chat: %s".formatted(error.getMessage());
        Log.error(message, error);

        return new ClaimBotQueryResponse("token", message, "");
    }

    @OnTextMessage
    @WithSpan(value = "ParasolAssistantChat", kind = SpanKind.SERVER)
    @ConversationBoundary
    public ClaimBotQueryResponse onMessage(ClaimBotQuery query) {
        var response = new ClaimBotQueryResponse("token", this.bot.chat(query), "");
        Log.debugf("Got chat response: %s", response);

        return response;
		}
}
