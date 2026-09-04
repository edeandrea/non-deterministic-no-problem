package ai.scoring.langfuse.config;

import java.time.Duration;
import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "quarkus.aiscoring.langfuse")
public interface LangfuseConfig {
	Evaluation evaluation();

	interface Evaluation {
		@WithDefault("true")
		boolean initializeOnStartup();

		Session session();
		Gemini gemini();

		// Gemini backs the Langfuse LLM-as-a-Judge evaluator (native Google AI Studio adapter).
		interface Gemini {
			@WithDefault("gemini-2.5-flash")
			String modelName();

			@WithDefault("${GEMINI_API_KEY:}")
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
