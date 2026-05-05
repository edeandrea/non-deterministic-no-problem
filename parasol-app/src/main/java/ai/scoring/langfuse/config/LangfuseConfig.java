package ai.scoring.langfuse.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "quarkus.aiscoring.langfuse")
public interface LangfuseConfig {
	String endpoint();

	@WithDefault("${quarkus.aiscoring.langfuse.endpoint}/api/public/otel/v1/traces")
	String otelEndpoint();

	String publicKey();
	String secretKey();

	@WithDefault("true")
	boolean onlyIncludeAiSpans();
}
