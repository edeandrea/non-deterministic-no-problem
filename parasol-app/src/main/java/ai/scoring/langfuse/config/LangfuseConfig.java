package ai.scoring.langfuse.config;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "quarkus.aiscoring.langfuse")
public interface LangfuseConfig {
	String endpoint();

	@WithDefault("${quarkus.aiscoring.langfuse.endpoint}/api/public/otel/v1/traces")
	String otelEndpoint();

	String publicKey();
	String secretKey();

	Scoring scoring();

	interface Scoring {
		@WithDefault("true")
		boolean initializeOnStartup();

		Cohere cohere();

		interface Cohere {
			@WithDefault("https://api.cohere.ai/compatibility/v1")
			String baseUrl();

			@WithDefault("command-r7b-12-2024")
			String modelName();

			@WithDefault("${COHERE_API_KEY:}")
			Optional<String> apiKey();
		}
	}
}
