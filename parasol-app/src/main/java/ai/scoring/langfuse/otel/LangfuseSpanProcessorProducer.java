package ai.scoring.langfuse.otel;

import java.util.Base64;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import ai.scoring.langfuse.config.LangfuseConfig;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

@ApplicationScoped
public class LangfuseSpanProcessorProducer {
  private final LangfuseConfig config;

  public LangfuseSpanProcessorProducer(LangfuseConfig config) {
    this.config = config;
  }

  @Produces
  @Singleton
  SpanProcessor langFuseSpanProcessor() {
    var credentials = "%s:%s".formatted(this.config.publicKey(), this.config.secretKey());
    var authHeader = "Basic %s".formatted(Base64.getEncoder().encodeToString(credentials.getBytes()));

    var exporter = OtlpHttpSpanExporter.builder()
                                       .setEndpoint(this.config.otelEndpoint())
                                       .addHeader("Authorization", authHeader)
                                       .addHeader("x-langfuse-ingestion-version", "1")
                                       .build();

    return BatchSpanProcessor.builder(new LangfuseSpanExporter(exporter, this.config))
                             .build();
  }
}
