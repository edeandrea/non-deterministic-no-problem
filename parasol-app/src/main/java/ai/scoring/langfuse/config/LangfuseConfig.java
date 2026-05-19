package ai.scoring.langfuse.config;

import java.time.Duration;
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

	Evaluation evaluation();

	interface Evaluation {
		@WithDefault("true")
		boolean initializeOnStartup();

		Session session();
		Cohere cohere();

		interface Cohere {
			@WithDefault("https://api.cohere.ai/compatibility/v1")
			String baseUrl();

			@WithDefault("command-r7b-12-2024")
			String modelName();

			@WithDefault("${COHERE_API_KEY:}")
			Optional<String> apiKey();
		}

		interface Session {
			@WithDefault("true")
			boolean createDatasetOnSessionClose();

			@WithDefault("true")
			boolean scoreSession();

			@WithDefault("5s")
			Duration otelFlushWaitTime();
		}
	}
}
