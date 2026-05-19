package ai.scoring.conversation;

import static jakarta.interceptor.Interceptor.Priority.APPLICATION;

import java.util.Optional;

import jakarta.annotation.Priority;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import io.quarkus.logging.Log;
import io.quarkus.websockets.next.WebSocketConnection;

import ai.scoring.conversation.Conversational.ConversationMode;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes;
import io.smallrye.mutiny.infrastructure.Infrastructure;

@Conversational
@Interceptor
@Priority(APPLICATION + 100)
public class ConversationalInterceptor {
	private final Event<ConversationCompletedEvent> conversationCompletedEvent;

	public ConversationalInterceptor(Event<ConversationCompletedEvent> conversationCompletedEvent) {
		this.conversationCompletedEvent = conversationCompletedEvent;
	}

	@AroundInvoke
	@SuppressWarnings("unused")
	public Object invoke(InvocationContext context) throws Exception {
		var conversationId = getConversationId();
		Span.current().setAttribute(GenAiIncubatingAttributes.GEN_AI_CONVERSATION_ID, conversationId);

		getConversationalAnnotation(context)
			.filter(conversational -> conversational.mode() == ConversationMode.COMPLETED)
			.ifPresent(conversational -> triggerConversationScoring(conversationId));

		return context.proceed();
	}

	private void triggerConversationScoring(String conversationId) {
		Log.infof("Conversation %s completed", conversationId);

		this.conversationCompletedEvent.fireAsync(
			ConversationCompletedEvent.builder()
			                          .conversationId(conversationId)
			                          .build(),
			NotificationOptions.builder()
				.setExecutor(Infrastructure.getDefaultExecutor())
				.build()
		);
	}

	private static String getConversationId() {
		return CDI.current().select(WebSocketConnection.class).get().id();
	}

	private static Optional<Conversational> getConversationalAnnotation(InvocationContext context) {
		return context.getInterceptorBindings().stream()
		              .filter(annotation -> annotation instanceof Conversational)
		              .map(Conversational.class::cast)
		              .findFirst();
	}
}
