package org.parasol.intake.observability;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import org.parasol.intake.observability.IntakeActivity.ReplySent;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;

/**
 * Puts what the intake's activity says on the span it happened in, as {@link IntakeMetrics} does for meters. A reply's template goes on the send's span:
 * {@code IntakeReplySender} fires {@link ReplySent} on the calling thread, inside its {@link MailClientSpan} span, so the span is the current one.
 * The name comes from a small fixed set, so it's safe on a span.
 */
@ApplicationScoped
class IntakeSpanAttributes {
	static final AttributeKey<String> TEMPLATE = AttributeKey.stringKey("claim.intake.reply.template");

	void onReplySent(@Observes ReplySent sent) {
		Span.current().setAttribute(TEMPLATE, sent.template());
	}
}
