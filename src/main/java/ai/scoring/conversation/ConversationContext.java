package ai.scoring.conversation;

import java.util.Optional;
import java.util.concurrent.Callable;

import jakarta.enterprise.context.ApplicationScoped;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes;

/**
 * The conversation id of the current work, carried as OpenTelemetry baggage under {@code gen_ai.conversation.id}, the
 * same key the chat conversations use. Enter it once around the code that starts a unit of work (e.g. an intake run) and
 * everything that inherits the OTel context sees it.
 */
@ApplicationScoped
public class ConversationContext {
	/**
	 * The baggage key: {@code gen_ai.conversation.id}.
	 */
	public static final String CONVERSATION_ID_KEY = GenAiIncubatingAttributes.GEN_AI_CONVERSATION_ID.getKey();

	/**
	 * The conversation id of the current OTel context.
	 *
	 * @return the id, or empty if no conversation was entered
	 */
	public Optional<String> currentId() {
		return Optional.ofNullable(Baggage.current().getEntryValue(CONVERSATION_ID_KEY));
	}

	/**
	 * Makes the conversation id current until the returned scope is closed.
	 *
	 * @param conversationId the conversation id
	 * @return the scope; close it, preferably with try-with-resources
	 */
	public Scope enter(String conversationId) {
		return Baggage.current()
			.toBuilder()
			.put(CONVERSATION_ID_KEY, conversationId)
			.build()
			.storeInContext(Context.current())
			.makeCurrent();
	}

	/**
	 * Runs a callable with the conversation id current.
	 *
	 * @param conversationId the conversation id
	 * @param callable what to run
	 * @param <T> the result type
	 * @return what the callable returned
	 */
	public <T> T callIn(String conversationId, Callable<T> callable) {
		try (var _ = enter(conversationId)) {
			return callable.call();
		}
		catch (RuntimeException e) {
			throw e;
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed in conversation " + conversationId, e);
		}
	}

	/**
	 * Runs a runnable with the conversation id current.
	 *
	 * @param conversationId the conversation id
	 * @param runnable what to run
	 */
	public void runIn(String conversationId, Runnable runnable) {
		try (var ignored = enter(conversationId)) {
			runnable.run();
		}
	}
}
