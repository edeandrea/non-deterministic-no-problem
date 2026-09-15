package ai.scoring.langfuse.otel;

import static ai.scoring.langfuse.otel.AiServiceAttributes.AI_SERVICES_PREFIX;
import static ai.scoring.langfuse.otel.AiServiceAttributes.AI_SERVICE_CLASS;
import static ai.scoring.langfuse.otel.AiServiceAttributes.AI_SERVICE_METHOD;
import static ai.scoring.langfuse.otel.AiServiceAttributes.DATASET_NAME;

import java.util.Optional;

import jakarta.inject.Singleton;

import io.quarkus.logging.Log;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

/**
 * An OpenTelemetry {@link SpanProcessor} that enriches AI service root spans
 * ({@code langchain4j.aiservices.<Service>.<method>}) with the {@link AiServiceAttributes dataset and service
 * coordinates}, cascading them down to child spans (generations, tool calls, and downstream operations) so that
 * the Langfuse observation for each generation carries the name of the dataset it belongs to.
 * <p>
 * The dataset name is the AI service span name, verbatim.
 */
@Singleton
class AiServiceDatasetSpanProcessor implements SpanProcessor {
	@Override
	public void onStart(Context parentContext, ReadWriteSpan span) {
		// Two cases:
		//   1. This span IS the AI service root span (langchain4j.aiservices.*) -> parse it and stamp the attributes on it
		//   2. Anything else -> inherit the attributes from the parent span, if the parent has them
		// Because case 2 copies from the *immediate* parent, and that parent was itself processed by this method when it
		// started, the attributes chain down through arbitrarily deep hierarchies (root -> tool -> completion, etc.)
		// without needing to walk up more than one level.
		Optional.ofNullable(span.getName())
			.filter(spanName -> spanName.startsWith(AI_SERVICES_PREFIX))
			.ifPresentOrElse(
				spanName -> enrichRootAiServiceSpan(span, spanName),
				() -> cascadeFromParent(parentContext, span)
			);
	}

	@Override
	public boolean isStartRequired() {
		// We need onStart so the attributes are present on the span *before* any child spans start
		// (children read them off the parent during their own onStart)
		return true;
	}

	@Override
	public void onEnd(ReadableSpan span) {
		// No-op: span enrichment occurs onStart
	}

	@Override
	public boolean isEndRequired() {
		// Nothing to do at end, so tell the SDK not to bother calling onEnd
		return false;
	}

	@Override
	public CompletableResultCode shutdown() {
		// Stateless: nothing to release
		return CompletableResultCode.ofSuccess();
	}

	@Override
	public CompletableResultCode forceFlush() {
		// Stateless: nothing buffered to flush
		return CompletableResultCode.ofSuccess();
	}

	/**
	 * Handles the AI service root span itself. Parses {@code langchain4j.aiservices.[pkg.]Service.method} into its
	 * class and method parts and stamps all three attributes on the span. The dataset name is the span name verbatim.
	 */
	private void enrichRootAiServiceSpan(ReadWriteSpan span, String spanName) {
		// e.g. "langchain4j.aiservices.AiServiceClassName.methodName" -> suffix = "AiServiceClassName.methodName"
		var suffix = spanName.substring(AI_SERVICES_PREFIX.length());
		var lastDot = suffix.lastIndexOf('.');

		// lastDot > 0 guards against a bare prefix ("langchain4j.aiservices.") or a suffix with no method part
		if (lastDot > 0) {
			// Everything before the last dot is the (possibly package-qualified) service; everything after is the method.
			// Quarkus LangChain4j emits the simple class name, but tolerate a package prefix by taking the last segment.
			var servicePart = suffix.substring(0, lastDot);
			var serviceClass = servicePart.substring(servicePart.lastIndexOf('.') + 1);
			var methodName = suffix.substring(lastDot + 1);

			// The dataset name is deliberately the full span name, not the parsed parts.
			// See AiServiceAttributes.DATASET_NAME for the rationale.
			span.setAttribute(DATASET_NAME, spanName);
			span.setAttribute(AI_SERVICE_CLASS, serviceClass);
			span.setAttribute(AI_SERVICE_METHOD, methodName);

			Log.debugf("Enriched AI service root span %s with dataset=%s (class=%s, method=%s)", spanName, spanName, serviceClass, methodName);
		}
	}

	/**
	 * Handles every non-root span. If the immediate parent carries a dataset name, copy it (and the class/method
	 * coordinates, if present) onto this span so the chain continues to the next level down.
	 */
	private void cascadeFromParent(Context parentContext, ReadWriteSpan span) {
		// Span.fromContext never returns null (it falls back to an invalid/no-op span), but only in-process spans created by
		// the SDK are ReadableSpan - remote parents (propagated over HTTP) and the no-op span aren't, so nothing can be
		// read off them and we simply skip.
		Optional.ofNullable(Span.fromContext(parentContext))
			.filter(ReadableSpan.class::isInstance)
			.map(ReadableSpan.class::cast)
			.ifPresent(readableParent ->
				// The dataset name is the trigger: no dataset name on the parent means we're not inside an AI service call
				Optional.ofNullable(readableParent.getAttribute(DATASET_NAME))
					.ifPresent(datasetName -> {
						span.setAttribute(DATASET_NAME, datasetName);

						// Class & method are copied independently so a parent that (for whatever reason) only has the dataset
						// name still cascades what it does have
						Optional.ofNullable(readableParent.getAttribute(AI_SERVICE_CLASS))
							.ifPresent(serviceClass -> span.setAttribute(AI_SERVICE_CLASS, serviceClass));

						Optional.ofNullable(readableParent.getAttribute(AI_SERVICE_METHOD))
							.ifPresent(serviceMethod -> span.setAttribute(AI_SERVICE_METHOD, serviceMethod));

						Log.debugf("Cascaded dataset=%s to child span %s", datasetName, span.getName());
					})
			);
	}
}
