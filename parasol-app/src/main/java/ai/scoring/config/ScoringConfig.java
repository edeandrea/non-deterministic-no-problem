package ai.scoring.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "quarkus.aiscoring")
public interface ScoringConfig {
	@WithDefault("NORMAL")
	InteractionMode interactionMode();

	@WithDefault("0.75")
	Double threshold();
}
