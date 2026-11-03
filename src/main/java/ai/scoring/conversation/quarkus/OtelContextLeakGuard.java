package ai.scoring.conversation.quarkus;

import java.util.Map;

import org.eclipse.microprofile.context.spi.ThreadContextController;
import org.eclipse.microprofile.context.spi.ThreadContextProvider;
import org.eclipse.microprofile.context.spi.ThreadContextSnapshot;

import io.opentelemetry.context.Context;
import io.quarkus.opentelemetry.runtime.QuarkusContextStorage;

/**
 * WORKAROUND for <a href="https://github.com/quarkusio/quarkus/issues/54354">quarkusio/quarkus#54354</a>, fixed by
 * <a href="https://github.com/quarkusio/quarkus/pull/56805">quarkusio/quarkus#56805</a> (4.0.0.Beta1) and backported
 * to 3.40 in <a href="https://github.com/quarkusio/quarkus/pull/57879">quarkusio/quarkus#57879</a>. <b>Delete this class
 * and its {@code META-INF/services} entry when the fix ships</b> in a Quarkus we're on (4.0, or a 3.40.x carrying
 * #57879). {@code OtelContextLeakGuardTests} then passes without it, which is the signal.
 * <p>
 * Quarkus's OpenTelemetry context-propagation provider attaches the submitter's context on the worker thread, but
 * only restores the worker's previous context if it had a recording span. A pool thread has none, so the task's
 * context (and the conversation id in its baggage) stays on the thread for the next task.
 * <p>
 * This provider is a second, independent context type. At the end of a task, if the worker thread (not the
 * submitting thread) still holds exactly the context that was propagated to it, nothing restored it, so it resets
 * the thread to {@link Context#root()}. SmallRye runs providers in no defined order, so this is right either way:
 * <ul>
 *   <li>Quarkus's provider ends first: it either restored the previous context (current != captured, leave it) or
 *   didn't (current == captured, reset).</li>
 *   <li>This one ends first: current == captured, reset; Quarkus's provider then restores a recording previous
 *   context if there was one, or does nothing.</li>
 * </ul>
 * Only touches thread-local storage; on a Vert.x duplicated context, Quarkus keeps the OTel context per duplicated
 * context and it isn't affected.
 * <p>
 * The one class of the conversation core that touches a Quarkus type, kept in its own package so it stays behind if
 * the core is extracted.
 */
public class OtelContextLeakGuard implements ThreadContextProvider {
	private static final String THREAD_CONTEXT_TYPE = "ConversationOtelContextLeakGuard";

	@Override
	public ThreadContextSnapshot currentContext(Map<String, String> props) {
		var captured = QuarkusContextStorage.INSTANCE.current();
		var submitter = Thread.currentThread();

		// Anonymous classes rather than lambdas, as Quarkus's own provider does, for the native image
		return new ThreadContextSnapshot() {
			@Override
			public ThreadContextController begin() {
				return new ThreadContextController() {
					@Override
					public void endContext() {
						if (isLeaked(captured, submitter)) {
							// Deliberately not closed: this replaces what the leaked attach left behind
							QuarkusContextStorage.INSTANCE.attach(Context.root());
						}
					}
				};
			}
		};
	}

	@Override
	public ThreadContextSnapshot clearedContext(Map<String, String> props) {
		return () -> () -> {};
	}

	@Override
	public String getThreadContextType() {
		return THREAD_CONTEXT_TYPE;
	}

	private static boolean isLeaked(Context captured, Thread submitter) {
		return (captured != null) &&
			(Thread.currentThread() != submitter) &&
			(QuarkusContextStorage.getVertxContext() == null) &&
			(QuarkusContextStorage.INSTANCE.current() == captured);
	}
}
