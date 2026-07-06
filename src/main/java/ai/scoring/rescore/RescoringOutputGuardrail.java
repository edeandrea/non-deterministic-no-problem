package ai.scoring.rescore;

import jakarta.enterprise.context.ApplicationScoped;

import ai.scoring.config.InteractionMode;
import ai.scoring.config.ScoringConfig;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.guardrail.OutputGuardrailResult;

@ApplicationScoped
public class RescoringOutputGuardrail implements OutputGuardrail {
	private final ScoringConfig scoringConfig;

	public RescoringOutputGuardrail(ScoringConfig scoringConfig) {
		this.scoringConfig = scoringConfig;
	}

	@Override
	public OutputGuardrailResult validate(OutputGuardrailRequest request) {
		if (this.scoringConfig.interactionMode() == InteractionMode.RESCORE) {
			// Need to trigger a rescore
		}

		return success();
	}
}
