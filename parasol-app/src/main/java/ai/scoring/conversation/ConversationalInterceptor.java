package ai.scoring.conversation;

import static jakarta.interceptor.Interceptor.Priority.APPLICATION;

import jakarta.annotation.Priority;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;

import io.quarkus.websockets.next.WebSocketConnection;

@ConversationBoundary
@Interceptor
@Priority(APPLICATION + 100)
public class ConversationalInterceptor {
	private static final String CONVERSATION_SPAN_NAME = "gen_ai.conversation.id";

	@AroundInvoke
	public Object invoke(InvocationContext context) throws Exception {
		var conversationId = getConversationId();
		Span.current().setAttribute(CONVERSATION_SPAN_NAME, conversationId);

		try (var scope = Context.current().makeCurrent()) {
			return context.proceed();
		}
	}

	public static String getConversationId() {
		return CDI.current().select(WebSocketConnection.class).get().id();
	}
}
