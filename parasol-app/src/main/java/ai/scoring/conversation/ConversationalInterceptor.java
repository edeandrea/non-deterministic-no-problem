package ai.scoring.conversation;

import static jakarta.interceptor.Interceptor.Priority.APPLICATION;

import jakarta.annotation.Priority;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes;

import io.quarkus.websockets.next.WebSocketConnection;

@Conversational
@Interceptor
@Priority(APPLICATION + 100)
public class ConversationalInterceptor {
	@AroundInvoke
	@SuppressWarnings("unused")
	public Object invoke(InvocationContext context) throws Exception {
		Span.current()
		    .setAttribute(GenAiIncubatingAttributes.GEN_AI_CONVERSATION_ID, getConversationId());

		return context.proceed();
	}

	private static String getConversationId() {
		return CDI.current().select(WebSocketConnection.class).get().id();
	}
}
