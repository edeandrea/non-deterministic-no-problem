package ai.scoring.conversation.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import jakarta.inject.Inject;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

import ai.scoring.conversation.ConversationContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;

// The regression test for quarkusio/quarkus#54354 (follow-up spike 3's probe). Once a Quarkus with the fix
// (quarkusio/quarkus#56805, or its 3.40 backport #57879) passes it without OtelContextLeakGuard, delete the guard.
@QuarkusTest
class OtelContextLeakGuardTests {
	private static final int TASKS_WITH_AN_ID = 32;
	private static final int TASKS_WITHOUT_ONE = 64;

	@Inject
	ManagedExecutor executor;

	@Inject
	ThreadContext threadContext;

	@Inject
	ConversationContext conversationContext;

	@Inject
	Tracer tracer;

	@Test
	void aPoolThreadDoesNotKeepAConversationPropagatedToIt() {
		var ids = IntStream.range(0, TASKS_WITH_AN_ID)
			.mapToObj("leak-%d"::formatted)
			.toList();

		var seenWithAnId = ids.stream()
			.map(id -> this.conversationContext.callIn(id, () -> this.executor.supplyAsync(this.conversationContext::currentId)))
			.toList()
			.stream()
			.map(CompletableFuture::join)
			.toList();

		var seenWithout = IntStream.range(0, TASKS_WITHOUT_ONE)
			.mapToObj(_ -> this.executor.supplyAsync(this.conversationContext::currentId))
			.toList()
			.stream()
			.map(CompletableFuture::join)
			.toList();

		assertThat(seenWithAnId).containsExactlyElementsOf(ids.stream().map(Optional::of).toList());
		assertThat(seenWithout)
			.hasSize(TASKS_WITHOUT_ONE)
			.allMatch(Optional::isEmpty);
	}

	@Test
	void aThreadKeepsItsOwnSpanAndConversationAfterRunningAContextualTaskInline() throws InterruptedException {
		var task = this.conversationContext.callIn("captured", () -> this.threadContext.contextualRunnable(() -> {}));
		var afterTask = new AtomicReference<List<Object>>();

		var thread = Thread.ofPlatform().start(() -> {
			var span = this.tracer.spanBuilder("leak-guard-test.own").startSpan();

			try (var _ = span.makeCurrent()) {
				this.conversationContext.runIn("own", () -> {
					task.run();
					afterTask.set(List.of(this.conversationContext.currentId(), Span.current().getSpanContext().equals(span.getSpanContext())));
				});
			}
			finally {
				span.end();
			}
		});
		thread.join();

		assertThat(afterTask.get()).containsExactly(Optional.of("own"), true);
	}
}
