package ai.scoring.scoring;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.logging.Log;

import ai.scoring.domain.interaction.Interaction;
import ai.scoring.domain.interaction.InteractionScore;
import ai.scoring.domain.interaction.RescoreResult;
import ai.scoring.evaluation.InteractionEvaluator;
import dev.langchain4j.model.scoring.ScoringModel;

@ApplicationScoped
public class InteractionScorer {
	private static final String INTERACTION_SCORED_EVENT_NAME = "interaction.scored";
	private static final String INTERACTION_RESCORED_EVENT_NAME = "interaction.rescored";

	public static final List<String> INTERACTION_EVENT_NAMES = List.of(INTERACTION_SCORED_EVENT_NAME, INTERACTION_RESCORED_EVENT_NAME);

	private final ScoringModel scoringModel;
	private final InteractionEvaluator interactionEvaluator;

	public InteractionScorer(ScoringModel scoringModel, InteractionEvaluator interactionEvaluator) {
		this.scoringModel = scoringModel;
		this.interactionEvaluator = interactionEvaluator;
	}

	public InteractionScore score(Interaction interaction) {
		return score(interaction, Instant.now());
	}

	@InteractionScored(
		name = INTERACTION_SCORED_EVENT_NAME,
		description = "An interaction that has been scored"
	)
	public InteractionScore score(Interaction interaction, Instant scoreTime) {
		var query = new StringBuilder();

		Optional.ofNullable(interaction.getSystemMessage())
		        .ifPresent(systemMessage -> query.append(systemMessage).append("\n\n"));

		Optional.ofNullable(interaction.getUserMessage())
			.ifPresent(query::append);

		var scoreResult = this.scoringModel.score(interaction.getResult(), query.toString());
		Log.infof("Interaction %s scored as %s", interaction.getInteractionId(), scoreResult.content());

		var score = InteractionScore.builder()
			.interaction(interaction)
			.score(scoreResult.content())
			.scoreDate(scoreTime)
			.build();

		interaction.getScores().add(score);

		return score;
	}

	public RescoreResult rescore(Interaction interaction) {
		var scoreDate = Instant.now();
		var evaluationReport = this.interactionEvaluator.evaluate(interaction);

		Log.debugf("Interaction %s rescored as %s", interaction.getInteractionId(), evaluationReport.score());

		var score = InteractionScore.builder()
			.interaction(interaction)
			.score(evaluationReport.score())
			.scoreDate(scoreDate)
			.build();

		interaction.getScores().add(score);

		return new RescoreResult(score, evaluationReport);
	}
}
