package org.parasol.intake;

import static ai.scoring.conversation.ConversationContext.CONVERSATION_ID_KEY;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

/**
 * Keeps finished spans only while a test explicitly captures them, so tests can check what a run's spans carry (its conversation id, its trace)
 * and that a run's {@code workflow.execute} span was exported (e.g. a canceled run's, quarkus-flow#1058). Quarkus registers
 * a {@code SpanExporter} CDI bean on its own.
 * <p>
 *   The bean is part of every test boot, but capture is off by default. {@link IntakeFlowTestBase} starts capture before
 *   each test and stops and clears it in teardown. Other consumers must manage the same lifecycle explicitly.
 * </p>
 */
@ApplicationScoped
public class TestSpans implements SpanExporter {
	public static final AttributeKey<String> CONVERSATION_ID = AttributeKey.stringKey(CONVERSATION_ID_KEY);
	public static final AttributeKey<String> INSTANCE_ID = AttributeKey.stringKey("flow.workflow.instance.id");
	public static final AttributeKey<String> WORKFLOW_NAME = AttributeKey.stringKey("flow.workflow.name");
	public static final AttributeKey<String> MESSAGING_SYSTEM = AttributeKey.stringKey("messaging.system");
	private static final String WORKFLOW_SPAN_PREFIX = "workflow.execute";

	// One monitor guards capture transitions, appends and snapshots. A stopped capture cannot be repopulated by
	// an export already in flight; appending does not copy all previous spans as CopyOnWriteArrayList did.
	private final List<SpanData> spans = new ArrayList<>();

	private boolean capturing;

	@Override
	public synchronized CompletableResultCode export(Collection<SpanData> exported) {
		if (this.capturing) {
			this.spans.addAll(exported);
		}
		return CompletableResultCode.ofSuccess();
	}

	@Override
	public CompletableResultCode flush() {
		return CompletableResultCode.ofSuccess();
	}

	@Override
	public CompletableResultCode shutdown() {
		stopCapture();
		return CompletableResultCode.ofSuccess();
	}

	/**
	 * The exported {@code workflow.execute} span of a run. Flow ends it when the run ends (completed, canceled or faulted),
	 * so a waiting run has none yet.
	 *
	 * @param runId The run's instance id
	 * @return The span, or empty if it hasn't been exported (yet)
	 */
	public Optional<SpanData> ofRun(String runId) {
		return all().stream()
			.filter(span -> span.getName().startsWith(WORKFLOW_SPAN_PREFIX))
			.filter(span -> runId.equals(span.getAttributes().get(INSTANCE_ID)))
			.findFirst();
	}

	/**
	 * Every exported span of a trace.
	 *
	 * @param traceId The trace id
	 * @return The spans, in the order they were exported
	 */
	public List<SpanData> ofTrace(String traceId) {
		return all().stream()
			.filter(span -> traceId.equals(span.getTraceId()))
			.toList();
	}

	/**
	 * Every exported span that carries a conversation id, in any trace.
	 *
	 * @param conversationId The conversation id
	 * @return The spans, in the order they were exported
	 */
	public List<SpanData> byConversation(String conversationId) {
		return all().stream()
			.filter(span -> conversationId.equals(conversationIdOf(span).orElse(null)))
			.toList();
	}

	/**
	 * Every span exported during the current capture, since its start or the last {@link #reset()}.
	 */
	public synchronized List<SpanData> all() {
		return List.copyOf(this.spans);
	}

	/**
	 * Starts a new capture, discarding the previous capture's spans. No size cap silently drops evidence within a test.
	 */
	public synchronized void startCapture() {
		this.spans.clear();
		this.capturing = true;
	}

	/**
	 * Stops capture and releases its retained spans. Later exports are ignored until capture starts again.
	 */
	public synchronized void stopCapture() {
		this.capturing = false;
		this.spans.clear();
	}

	/**
	 * Clears retained spans without changing whether capture is active.
	 */
	public synchronized void reset() {
		this.spans.clear();
	}

	public static Optional<String> conversationIdOf(SpanData span) {
		return Optional.ofNullable(span.getAttributes().get(CONVERSATION_ID));
	}
}
