package ai.scoring.mapping;

import jakarta.enterprise.context.ApplicationScoped;

import ai.scoring.rescore.RescoreResult;
import ai.scoring.scorer.model.SubmitInteractionEvent200Response;

@ApplicationScoped
public class RescoreInteractionResultMapper {
	public RescoreResult map(SubmitInteractionEvent200Response response) {
		if (response == null) {
			throw new IllegalArgumentException("response must not be null");
		}

		return new RescoreResult(response.getInteractionId(), response.getInteractionDate(), response.getScoreDate(), response.getScore());
	}}
