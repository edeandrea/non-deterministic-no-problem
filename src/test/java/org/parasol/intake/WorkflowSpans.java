package org.parasol.intake;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.enterprise.context.ApplicationScoped;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

/**
 * Collects the finished {@code workflow.execute} spans quarkus-flow exports, one per run, so tests can check a run's span
 * was exported (e.g. a canceled run's, quarkus-flow#1058). Quarkus registers a {@code SpanExporter} CDI bean on its own.
 * Every other span is dropped, since the bean is part of every test boot.
 */
@ApplicationScoped
public class WorkflowSpans implements SpanExporter {
	private static final String WORKFLOW_SPAN_PREFIX = "workflow.execute";
	private static final AttributeKey<String> INSTANCE_ID = AttributeKey.stringKey("flow.workflow.instance.id");

	private final List<SpanData> spans = new CopyOnWriteArrayList<>();

	@Override
	public CompletableResultCode export(Collection<SpanData> exported) {
		exported.stream()
			.filter(span -> span.getName().startsWith(WORKFLOW_SPAN_PREFIX))
			.forEach(this.spans::add);

		return CompletableResultCode.ofSuccess();
	}

	@Override
	public CompletableResultCode flush() {
		return CompletableResultCode.ofSuccess();
	}

	@Override
	public CompletableResultCode shutdown() {
		return CompletableResultCode.ofSuccess();
	}

	/**
	 * The exported {@code workflow.execute} span of a run.
	 *
	 * @param runId The run's instance id
	 * @return The span, or empty if it hasn't been exported (yet)
	 */
	public Optional<SpanData> ofRun(String runId) {
		return this.spans.stream()
			.filter(span -> runId.equals(span.getAttributes().get(INSTANCE_ID)))
			.findFirst();
	}

	public void reset() {
		this.spans.clear();
	}
}
