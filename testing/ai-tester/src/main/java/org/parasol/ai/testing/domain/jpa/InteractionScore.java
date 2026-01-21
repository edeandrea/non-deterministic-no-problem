package org.parasol.ai.testing.domain.jpa;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "interaction_scores")
public class InteractionScore {
	@Id
	@ManyToOne
	@JoinColumn(name = "interaction_id", nullable = false)
	@NotNull(message = "interaction must not be null")
	private Interaction interaction;

	@Id
	@Column(nullable = false)
	@NotNull(message = "scoreDate must not be null")
	private Instant scoreDate;

	@Column(nullable = false)
	@NotNull(message = "score must not be null")
	private Double score;

	public InteractionScore() {
	}

	private InteractionScore(Builder builder) {
		this.interaction = builder.interaction;
		this.scoreDate = builder.scoreDate;
		this.score = builder.score;

		if (this.interaction == null) {
			throw new IllegalArgumentException("interaction must not be null");
		}

		if (this.scoreDate == null) {
			throw new IllegalArgumentException("scoreDate must not be null");
		}

		if (this.score == null) {
			throw new IllegalArgumentException("score must not be null");
		}
	}

	public Interaction getInteraction() {
		return interaction;
	}

	public Instant getScoreDate() {
		return scoreDate;
	}

	public Double getScore() {
		return score;
	}

	public Builder toBuilder() {
		return new Builder(this);
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public String toString() {
		return "InteractionScore{" +
			"interactionId=" + (interaction != null ? interaction.getInteractionId() : null) +
			", scoreDate=" + scoreDate +
			", score=" + score +
			'}';
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof InteractionScore that)) {
			return false;
		}

		return Objects.equals(interaction, that.interaction) && Objects.equals(scoreDate, that.scoreDate);
	}

	@Override
	public int hashCode() {
		return Objects.hash(interaction, scoreDate);
	}

	public static class Builder {
		private Interaction interaction;
		private Instant scoreDate;
		private Double score;

		private Builder() {
		}

		private Builder(InteractionScore source) {
			this.interaction = source.interaction;
			this.scoreDate = source.scoreDate;
			this.score = source.score;
		}

		public Builder interaction(Interaction interaction) {
			this.interaction = interaction;
			return this;
		}

		public Builder scoreDate(Instant scoreDate) {
			this.scoreDate = scoreDate;
			return this;
		}

		public Builder score(Double score) {
			this.score = score;
			return this;
		}

		public InteractionScore build() {
			return new InteractionScore(this);
		}
	}
}
