package ai.scoring.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import jakarta.inject.Inject;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadableSpan;

// The application's own tracer, so this also proves Quarkus registered the processor. The spans are read back as the SDK's
// ReadableSpan rather than exported: %test exports nothing.
@QuarkusTest
class ConversationIdSpanProcessorTests {
	private static final AttributeKey<String> CONVERSATION_ID = AttributeKey.stringKey(ConversationContext.CONVERSATION_ID_KEY);
	private static final int CONCURRENT_RUNS = 8;

	@Inject
	Tracer tracer;

	@Inject
	ConversationContext conversationContext;

	@Inject
	ManagedExecutor executor;

	@Test
	void aSpanStartedInAConversationCarriesItsIdAndOneStartedOutsideNone() {
		var inside = this.conversationContext.callIn("span-conversation", this::conversationIdOfANewSpan);

		assertThat(inside).contains("span-conversation");
		assertThat(conversationIdOfANewSpan()).isEmpty();
	}

	@Test
	void theIdComesFromTheParentContextNotTheCurrentOne() {
		var parent = Baggage.current()
			.toBuilder()
			.put(ConversationContext.CONVERSATION_ID_KEY, "explicit-parent")
			.build()
			.storeInContext(Context.root());

		var span = this.tracer.spanBuilder("conversation-test")
			.setParent(parent)
			.startSpan();

		try {
			assertThat(conversationIdOf(span)).contains("explicit-parent");
		}
		finally {
			span.end();
		}
	}

	@Test
	void concurrentConversationsOnTheManagedExecutorEachStampOnlyTheirOwnId() {
		// Every run waits for the others before starting its spans, so all of them are in flight at once
		var barrier = new CyclicBarrier(CONCURRENT_RUNS);
		var ids = IntStream.range(0, CONCURRENT_RUNS)
			.mapToObj("concurrent-%d"::formatted)
			.toList();

		var runs = ids.stream()
			.map(id -> this.conversationContext.callIn(id, () -> this.executor.supplyAsync(() -> spanTree(barrier))))
			.toList();

		assertThat(runs.stream().map(CompletableFuture::join).toList())
			.containsExactlyElementsOf(ids.stream().map(id -> List.of(Optional.of(id), Optional.of(id))).toList());
	}

	private List<Optional<String>> spanTree(CyclicBarrier barrier) {
		await(barrier);
		var parent = this.tracer.spanBuilder("conversation-test.parent").startSpan();

		try (var _ = parent.makeCurrent()) {
			return List.of(conversationIdOf(parent), conversationIdOfANewSpan());
		}
		finally {
			parent.end();
		}
	}

	private Optional<String> conversationIdOfANewSpan() {
		var span = this.tracer.spanBuilder("conversation-test").startSpan();

		try {
			return conversationIdOf(span);
		}
		finally {
			span.end();
		}
	}

	private static Optional<String> conversationIdOf(Span span) {
		return Optional.ofNullable(((ReadableSpan) span).getAttribute(CONVERSATION_ID));
	}

	private static void await(CyclicBarrier barrier) {
		try {
			barrier.await(30, TimeUnit.SECONDS);
		}
		catch (Exception e) {
			throw new IllegalStateException("The concurrent runs never all started", e);
		}
	}
}
