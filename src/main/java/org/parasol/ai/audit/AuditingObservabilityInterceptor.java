package org.parasol.ai.audit;

import java.util.Arrays;
import java.util.Optional;

import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import io.quarkus.opentelemetry.runtime.config.build.OTelBuildConfig;

import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.quarkiverse.langchain4j.audit.AuditSourceInfo;
import io.quarkiverse.langchain4j.audit.LLMInteractionEvent;
import io.quarkiverse.langchain4j.audit.ResponseFromLLMReceivedEvent;
import io.quarkiverse.langchain4j.audit.ToolExecutedEvent;

@AuditObserved
@Interceptor
@Priority(Interceptor.Priority.APPLICATION + 200)
public class AuditingObservabilityInterceptor {
	private final OTelBuildConfig otelConfig;
	private final Meter meter;
	private final Tracer tracer;

	public AuditingObservabilityInterceptor(OTelBuildConfig otelConfig, Meter meter, Tracer tracer) {
		this.otelConfig = otelConfig;
		this.meter = meter;
		this.tracer = tracer;
	}

	@AroundInvoke
	public Object invoke(InvocationContext context) throws Exception {
		var auditObservedAnnotation = getAuditObservedAnnotation(context);

		return (isOtelEnabled() && auditObservedAnnotation.isPresent()) ?
		       wrap(context, auditObservedAnnotation.get()) :
		       context.proceed();
	}

	private boolean isOtelEnabled() {
		return this.otelConfig.enabled();
	}

	private boolean isOtelMetricsEnabled() {
		return isOtelEnabled() && this.otelConfig.metrics().enabled().orElse(false);
	}

	private Object wrap(InvocationContext context, AuditObserved auditObserved) throws Exception {
		var interactionEvent = getInteractionEvent(context);
		var sourceInfo = interactionEvent.map(LLMInteractionEvent::sourceInfo).orElseThrow();
		var spanAttributes = Attributes.builder()
		                               .put("arg.interfaceName", sourceInfo.interfaceName())
		                               .put("arg.methodName", sourceInfo.methodName());
		var metricAttributes = Attributes.builder()
		                                 .put("interfaceName", sourceInfo.interfaceName())
		                                 .put("methodName", sourceInfo.methodName());

		interactionEvent.filter(event -> event instanceof ToolExecutedEvent)
		                .map(ToolExecutedEvent.class::cast)
		                .map(event -> event.request().name())
		                .ifPresent(toolName -> {
			                spanAttributes.put("arg.toolName", toolName);
			                metricAttributes.put("toolName", toolName);
		                });

		var span = this.tracer.spanBuilder(auditObserved.name())
		                      .setParent(Context.current().with(Span.current()))
		                      .setSpanKind(SpanKind.INTERNAL)
		                      .setAllAttributes(spanAttributes.build())
		                      .startSpan();

		try {
			return context.proceed();
		}
		finally {
			span.end();

			if (isOtelMetricsEnabled()) {
				addToCounter(
					auditObserved.name(),
					auditObserved.description(),
					auditObserved.unit(),
					sourceInfo
				);

				interactionEvent.filter(event -> event instanceof ResponseFromLLMReceivedEvent)
				                .map(ResponseFromLLMReceivedEvent.class::cast)
				                .map(event -> event.response().metadata())
				                .ifPresent(this::addToTotalTokenCount);
			}
		}
	}

	private Optional<LLMInteractionEvent> getInteractionEvent(InvocationContext context) {
		return Arrays.stream(context.getParameters())
		             .filter(param -> param instanceof LLMInteractionEvent)
		             .map(LLMInteractionEvent.class::cast)
		             .findFirst();
	}

	private Optional<AuditObserved> getAuditObservedAnnotation(InvocationContext context) {
		return context.getInterceptorBindings().stream()
		              .filter(annotation -> annotation instanceof AuditObserved)
		              .map(AuditObserved.class::cast)
		              .filter(auditObserved -> !auditObserved.name().strip().isBlank())
		              .findFirst();
	}

	private void addToCounter(String name, String description, String unit, AuditSourceInfo sourceInfo) {
		this.meter.counterBuilder(name)
		          .setDescription(description)
		          .setUnit(unit)
		          .build()
		          .add(
			          1,
			          Attributes.builder()
			                    .put("interfaceName", sourceInfo.interfaceName())
			                    .put("methodName", sourceInfo.methodName())
			                    .build()
		          );
	}

	private void addToTotalTokenCount(ChatResponseMetadata metadata) {
		var modelNameAttributes = Attributes.of(AttributeKey.stringKey("modelName"), metadata.modelName());
		var inputTokenCounter = this.meter.counterBuilder("parasol.llm.token.input.count")
		          .setDescription("Total input token count")
		          .setUnit("tokens")
		          .build();

		inputTokenCounter.add(metadata.tokenUsage().inputTokenCount(), modelNameAttributes);
		inputTokenCounter.add(metadata.tokenUsage().inputTokenCount());

		var outputTokenCounter = this.meter.counterBuilder("parasol.llm.token.output.count")
		          .setDescription("Total output token count")
		          .setUnit("tokens")
		          .build();

		outputTokenCounter.add(metadata.tokenUsage().outputTokenCount(), modelNameAttributes);
		outputTokenCounter.add(metadata.tokenUsage().outputTokenCount());

		var totalTokenCounter = this.meter.counterBuilder("parasol.llm.token.total.count")
		          .setDescription("Total token count")
		          .setUnit("tokens")
		          .build();

		totalTokenCounter.add(metadata.tokenUsage().totalTokenCount(), modelNameAttributes);
		totalTokenCounter.add(metadata.tokenUsage().totalTokenCount());
	}
}
