package org.parasol.ai.scoring.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;

import org.parasol.ai.scoring.domain.score.Interaction;
import org.parasol.ai.scoring.domain.score.InteractionScore;

import dev.langchain4j.model.scoring.ScoringModel;

import io.quarkus.logging.Log;

@ApplicationScoped
public class InteractionScorer {
	private final ScoringModel scoringModel;

	public InteractionScorer(ScoringModel scoringModel) {
		this.scoringModel = scoringModel;
	}

	public Stream<InteractionScore> scoreAll(List<Interaction> interactions, Instant scoreTime) {
		return interactions.stream()
			.map(interaction -> score(interaction, scoreTime));
	}

	public InteractionScore score(Interaction interaction) {
		return score(interaction, Instant.now());
	}

	@InteractionScored(
		name = "interaction.scored",
		description = "An interaction that has been scored",
		unit = "score"
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
}
