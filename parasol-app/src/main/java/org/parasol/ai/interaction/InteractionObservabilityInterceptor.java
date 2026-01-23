package org.parasol.ai.interaction;

import java.util.Arrays;
import java.util.Optional;

import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.observability.api.event.AiServiceEvent;
import dev.langchain4j.observability.api.event.AiServiceResponseReceivedEvent;
import dev.langchain4j.observability.api.event.ToolExecutedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;

import io.quarkus.opentelemetry.runtime.config.build.OTelBuildConfig;

@InteractionObserved
@Interceptor
@Priority(Interceptor.Priority.APPLICATION + 200)
public class InteractionObservabilityInterceptor {
	private final OTelBuildConfig otelConfig;
	private final MeterRegistry meterRegistry;
	private final Tracer tracer;

	public InteractionObservabilityInterceptor(OTelBuildConfig otelConfig, MeterRegistry meterRegistry, Tracer tracer) {
		this.otelConfig = otelConfig;
		this.meterRegistry = meterRegistry;
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

	private Object wrap(InvocationContext context, InteractionObserved auditObserved) throws Exception {
		var interactionEvent = getAiServiceEvent(context);
		var invocationContext = interactionEvent.map(AiServiceEvent::invocationContext).orElseThrow();
		var spanAttributes = Attributes.builder()
		                               .put("arg.interfaceName", invocationContext.interfaceName())
		                               .put("arg.methodName", invocationContext.methodName());
		var metricTags = Tags.of(
			Tag.of("interfaceName", invocationContext.interfaceName()),
			Tag.of("methodName", invocationContext.methodName()));

		var allTags = metricTags.and(
			interactionEvent.filter(ToolExecutedEvent.class::isInstance)
			                .map(ToolExecutedEvent.class::cast)
			                .map(event -> event.request().name())
			                .map(String::strip)
			                .filter(toolName -> !toolName.isBlank())
			                .map(toolName -> {
												spanAttributes.put("arg.toolName", toolName);
												return metricTags.and("toolName", toolName.strip());
											})
			                .orElseGet(Tags::empty)
		);

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
					allTags
				);

				interactionEvent.filter(event -> event instanceof AiServiceResponseReceivedEvent)
				                .map(AiServiceResponseReceivedEvent.class::cast)
				                .map(event -> event.response().metadata())
				                .ifPresent(this::addToTotalTokenCount);
			}
		}
	}

	private Optional<AiServiceEvent> getAiServiceEvent(InvocationContext context) {
		return Arrays.stream(context.getParameters())
		             .filter(param -> param instanceof AiServiceEvent)
		             .map(AiServiceEvent.class::cast)
		             .findFirst();
	}

	private Optional<InteractionObserved> getAuditObservedAnnotation(InvocationContext context) {
		return context.getInterceptorBindings().stream()
		              .filter(annotation -> annotation instanceof InteractionObserved)
		              .map(InteractionObserved.class::cast)
		              .filter(auditObserved -> !auditObserved.name().strip().isBlank())
		              .findFirst();
	}

	private void addToCounter(String name, String description, String unit, Tags metricTags) {
		Counter.builder(name)
			.description(description)
			.baseUnit(unit)
			.tags(metricTags)
			.register(this.meterRegistry)
			.increment();
	}

	private void addToTotalTokenCount(ChatResponseMetadata metadata) {
		var modelNameTags = Tags.of("modelName", metadata.modelName());
		var inputTokenCounterBuilder = Counter.builder("parasol.llm.token.input.count")
			.description("Total input token count")
			.baseUnit("tokens");

		inputTokenCounterBuilder
			.tags(modelNameTags)
			.register(this.meterRegistry)
			.increment(metadata.tokenUsage().inputTokenCount());

		inputTokenCounterBuilder
			.register(this.meterRegistry)
			.increment(metadata.tokenUsage().inputTokenCount());

		var outputTokenCounterBuilder = Counter.builder("parasol.llm.token.output.count")
			.description("Total output token count")
			.baseUnit("tokens");

		outputTokenCounterBuilder
			.tags(modelNameTags)
			.register(this.meterRegistry)
			.increment(metadata.tokenUsage().outputTokenCount());

		outputTokenCounterBuilder
			.register(this.meterRegistry)
			.increment(metadata.tokenUsage().outputTokenCount());

		var totalTokenCounterBuilder = Counter.builder("parasol.llm.token.total.count")
			.description("Total token count")
			.baseUnit("tokens");

		totalTokenCounterBuilder
			.tags(modelNameTags)
			.register(this.meterRegistry)
			.increment(metadata.tokenUsage().totalTokenCount());

		totalTokenCounterBuilder
			.register(this.meterRegistry)
			.increment(metadata.tokenUsage().totalTokenCount());
	}
}
