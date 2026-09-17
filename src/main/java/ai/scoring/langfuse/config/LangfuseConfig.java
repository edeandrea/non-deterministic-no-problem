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

			/**
			 * How long to wait after a conversation ends before first querying Langfuse for its observations.
			 * Gives the OpenTelemetry batch exporter time to flush the spans.
			 */
			@WithDefault("5s")
			Duration otelFlushWaitTime();

			/**
			 * How often to re-query Langfuse while its observations for the session are still empty.
			 * Langfuse ingests OTLP traces asynchronously, so they are not queryable the instant they are exported.
			 */
			@WithDefault("2s")
			Duration observationPollInterval();

			/**
			 * Upper bound on the total time (including {@link #otelFlushWaitTime()}) to wait for the session's
			 * observations to become queryable before giving up on scoring the session.
			 */
			@WithDefault("30s")
			Duration observationMaxWaitTime();

			/**
			 * Upper bound on the total time to wait for the session's datasets and dataset items to be recorded
			 * in Langfuse before giving up.
			 */
			@WithDefault("30s")
			Duration datasetCreationMaxWaitTime();
		}
	}
}
