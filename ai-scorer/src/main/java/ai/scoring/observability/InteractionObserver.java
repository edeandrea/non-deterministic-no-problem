package ai.scoring.observability;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter.MeterProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import io.quarkus.logging.Log;
import io.quarkus.opentelemetry.runtime.config.build.OTelBuildConfig;

@ApplicationScoped
public class InteractionObserver implements ChatModelListener {
	private MeterProvider<Counter> requestStartedCounter;
	private MeterProvider<Counter> responseReceivedCounter;
	private MeterProvider<Counter> requestFailedCounter;
	private MeterProvider<Counter> totalInputTokenCounter;
	private MeterProvider<Counter> totalOutputTokenCounter;
	private MeterProvider<Counter> totalTokenCounter;

	public InteractionObserver(OTelBuildConfig otelConfig, MeterRegistry meterRegistry) {
		if (isOtelMetricsEnabled(otelConfig)) {
			this.requestStartedCounter = createMeterProvider(meterRegistry, "scorer.interaction.started", "A count of LLM interactions started", "service interactions started");
			this.responseReceivedCounter = createMeterProvider(meterRegistry, "scorer.response.received", "A count of LLM responses received", "received responses");
			this.requestFailedCounter = createMeterProvider(meterRegistry, "scorer.interaction.failed", "A count of LLM interactions failed", "failed interactions");
			this.totalInputTokenCounter = createMeterProvider(meterRegistry, "scorer.token.input.count", "Total input token count", "tokens");
			this.totalOutputTokenCounter = createMeterProvider(meterRegistry, "scorer.token.output.count", "Total output token count", "tokens");
			this.totalTokenCounter = createMeterProvider(meterRegistry, "scorer.token.total.count", "Total token count", "tokens");
		}
	}

	private static MeterProvider<Counter> createMeterProvider(MeterRegistry meterRegistry, String name, String description, String unit) {
		return Counter.builder(name)
			.description(description)
			.baseUnit(unit)
			.withRegistry(meterRegistry);
	}

	private boolean isOtelEnabled(OTelBuildConfig otelConfig) {
		return otelConfig.enabled();
	}

	private boolean isOtelMetricsEnabled(OTelBuildConfig otelConfig) {
		return isOtelEnabled(otelConfig) && otelConfig.metrics().enabled().orElse(false);
	}

	private void incrementTotals(TokenUsage tokenUsage, String modelName) {
		var modelNameTags = Tags.of("modelName", modelName);
		incrementMeter(this.totalInputTokenCounter, tokenUsage.inputTokenCount());
		incrementMeter(this.totalInputTokenCounter, modelNameTags, tokenUsage.inputTokenCount());
		incrementMeter(this.totalOutputTokenCounter, tokenUsage.outputTokenCount());
		incrementMeter(this.totalOutputTokenCounter, modelNameTags, tokenUsage.outputTokenCount());
		incrementMeter(this.totalTokenCounter, tokenUsage.totalTokenCount());
		incrementMeter(this.totalTokenCounter, modelNameTags, tokenUsage.totalTokenCount());
	}

	private void incrementMeter(MeterProvider<Counter> meter, int incrementBy) {
		incrementMeter(meter, Tags.empty(), incrementBy);
	}

	private void incrementMeter(MeterProvider<Counter> meter, Tags tags, int incrementBy) {
		if (meter != null) {
			meter.withTags(tags).increment(incrementBy);
		}
	}

	@Override
	public void onRequest(ChatModelRequestContext requestContext) {
		Log.infof("Request started: %s", requestContext.chatRequest());
		incrementMeter(this.requestStartedCounter, Tags.of("modelName", requestContext.chatRequest().modelName()), 1);
	}

	@Override
	public void onResponse(ChatModelResponseContext responseContext) {
		Log.infof("Response received: %s", responseContext.chatResponse());

		var modelName = responseContext.chatRequest().modelName();
		incrementMeter(this.responseReceivedCounter, Tags.of("modelName", modelName), 1);
		incrementTotals(responseContext.chatResponse().tokenUsage(), modelName);
	}

	@Override
	public void onError(ChatModelErrorContext errorContext) {
		Log.infof("Request failed: %s", errorContext.error().getMessage());
		incrementMeter(this.responseReceivedCounter, Tags.of("modelName", errorContext.chatRequest().modelName()), 1);
	}
}
