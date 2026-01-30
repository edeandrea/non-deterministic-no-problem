package ai.scoring.scoring;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import ai.scoring.domain.interaction.Interaction;
import ai.scoring.domain.interaction.InteractionScore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter.MeterProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;

import io.quarkus.opentelemetry.runtime.config.build.OTelBuildConfig;

@InteractionScored
@Interceptor
@Priority(Interceptor.Priority.APPLICATION + 200)
public class InteractionScoringInterceptor {
	private final OTelBuildConfig otelConfig;
	private final MeterRegistry meterRegistry;
	private final Tracer tracer;
	private final Map<Tags, AtomicDouble> gauges = new ConcurrentHashMap<>();
	private final Map<String, MeterProvider<Counter>> counterMeters = new ConcurrentHashMap<>();

	public InteractionScoringInterceptor(OTelBuildConfig otelConfig, MeterRegistry meterRegistry, Tracer tracer) {
		this.otelConfig = otelConfig;
		this.meterRegistry = meterRegistry;
		this.tracer = tracer;
	}

	@AroundInvoke
	public Object invoke(InvocationContext context) throws Exception {
		if (isOtelEnabled()) {
			var interactionScoredAnnotation = getInteractionScoredAnnotation(context);

			if (interactionScoredAnnotation.isPresent()) {
				return wrap(context, interactionScoredAnnotation.get());
			}
		}

		return context.proceed();
	}

	private MeterProvider<Counter> getCounterMeter(String name, String description) {
		return this.counterMeters.computeIfAbsent(
			name,
			n -> Counter.builder(n)
				.description(description)
				.baseUnit("interaction")
				.withRegistry(this.meterRegistry)
		);
	}

	private Object wrap(InvocationContext context, InteractionScored interactionScored) throws Exception {
		var interaction = getInteraction(context).orElseThrow(() -> new IllegalStateException("No interaction found in invocation context - this can't be true!!"));
		var spanAttributes = Attributes.builder()
		                               .put("arg.applicationName", interaction.getApplicationName())
		                               .put("arg.interfaceName", interaction.getInterfaceName())
		                               .put("arg.methodName", interaction.getMethodName());

		var span = this.tracer.spanBuilder(interactionScored.name())
		                      .setParent(Context.current().with(Span.current()))
		                      .setSpanKind(SpanKind.INTERNAL)
		                      .setAllAttributes(spanAttributes.build())
		                      .startSpan();

		try (var scope = span.makeCurrent()) {
			try {
				var result = context.proceed();

				if (isOtelMetricsEnabled()) {
					processInvocationScore(result, interactionScored);
				}

				return result;
			}
			finally {
				span.end();
			}
		}
	}

	private void processInvocationScore(Object result, InteractionScored interactionScored) {
		if (result instanceof InteractionScore score) {
			var interaction = score.getInteraction();
			var tags = Tags.of(
				Tag.of("applicationName", interaction.getApplicationName()),
				Tag.of("interfaceName", interaction.getInterfaceName()),
				Tag.of("methodName", interaction.getMethodName()));

			this.gauges.computeIfAbsent(tags, tagsKey -> {
				var gauge = new AtomicDouble(0.0);
				Gauge.builder("%s.latest".formatted(interactionScored.name()), gauge, AtomicDouble::get)
				     .description(interactionScored.description())
				     .baseUnit("score")
				     .tags(tags)
				     .register(this.meterRegistry);

				return gauge;
			}).set(score.getScore());

			getCounterMeter(interactionScored.name(), interactionScored.description())
				.withTags(tags)
				.increment();
			//			DistributionSummary.builder(interactionScored.name())
			//			                   .description(interactionScored.description())
			//			                   .baseUnit(interactionScored.unit())
			//			                   .tags(tags)
			//			                   .maximumExpectedValue(1.0)
			//			                   .publishPercentileHistogram()
			//			                   .serviceLevelObjectives(0.5, 0.75, 0.9)
			//			                   .register(this.meterRegistry)
			//			                   .record(interaction.getScore());
		}
	}

	private boolean isOtelMetricsEnabled() {
		return isOtelEnabled() && this.otelConfig.metrics().enabled().orElse(false);
	}

	private boolean isOtelEnabled() {
		return this.otelConfig.enabled();
	}

	private static Optional<Interaction> getInteraction(InvocationContext context) {
		return Arrays.stream(context.getParameters())
		             .filter(Interaction.class::isInstance)
		             .map(Interaction.class::cast)
		             .findFirst();
	}

	private static Optional<InteractionScored> getInteractionScoredAnnotation(InvocationContext context) {
		return context.getInterceptorBindings().stream()
		              .filter(InteractionScored.class::isInstance)
		              .map(InteractionScored.class::cast)
		              .filter(interactionScored -> !interactionScored.name().strip().isBlank())
		              .findFirst();
	}

	private static class AtomicDouble {
		private final AtomicLong bits;

		private AtomicDouble(double initialValue) {
			bits = new AtomicLong(Double.doubleToLongBits(initialValue));
		}

		public double get() {
			return Double.longBitsToDouble(bits.get());
		}

		public void set(double newValue) {
			bits.set(Double.doubleToLongBits(newValue));
		}
	}
}
