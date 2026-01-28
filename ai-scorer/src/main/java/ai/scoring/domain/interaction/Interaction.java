package ai.scoring.domain.interaction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQueries;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "interactions")
@NamedQueries({
	@NamedQuery(name = "Interaction.findAllBySource", query = "FROM Interaction WHERE applicationName = :applicationName AND interfaceName = :interfaceName AND methodName = :methodName"),
	@NamedQuery(name = "Interaction.countBySource", query = "SELECT count(*)FROM Interaction WHERE applicationName = :applicationName AND interfaceName = :interfaceName AND methodName = :methodName")
})
public class Interaction {
	@Id
	@NotNull(message = "interactionId must not be null")
	private UUID interactionId;

	@Column(nullable = false)
	@NotNull(message = "interactionDate must not be null")
	private Instant interactionDate;

	private String applicationName;
	private String interfaceName;
	private String methodName;

	@Column(columnDefinition = "TEXT")
	private String systemMessage;

	@Column(columnDefinition = "TEXT")
	private String userMessage;

	@Column(columnDefinition = "TEXT")
	private String result;

	@OneToMany(
		mappedBy = "interaction",
		cascade = CascadeType.ALL,
		orphanRemoval = true,
		fetch = FetchType.EAGER
	)
	private List<InteractionScore> scores = new ArrayList<>();

	public Interaction() {
	}

	private Interaction(Builder builder) {
		this.interactionId = builder.interactionId;
		this.applicationName = builder.applicationName;
		this.interfaceName = builder.interfaceName;
		this.methodName = builder.methodName;
		this.interactionDate = builder.interactionDate;
		this.systemMessage = builder.systemMessage;
		this.userMessage = builder.userMessage;
		this.result = builder.result;
		this.scores.addAll(builder.scores);

		if (this.interactionId == null) {
			throw new IllegalArgumentException("interactionId must not be null");
		}

		if (this.interactionDate == null) {
			throw new IllegalArgumentException("interactionDate must not be null");
		}

		if (this.applicationName == null) {
			throw new IllegalArgumentException("interactionUri must not be null");
		}
	}

	public UUID getInteractionId() {
		return interactionId;
	}

	public String getApplicationName() {
		return applicationName;
	}

	public String getInterfaceName() {
		return interfaceName;
	}

	public String getMethodName() {
		return methodName;
	}

	public Instant getInteractionDate() {
		return interactionDate;
	}

	public String getSystemMessage() {
		return systemMessage;
	}

	public String getUserMessage() {
		return userMessage;
	}

	public String getResult() {
		return result;
	}

	public List<InteractionScore> getScores() {
		return scores;
	}

	public Builder toBuilder() {
		return new Builder(this);
	}

	public static Builder builder() {
		return new Builder();
	}
	
	@Override
	public String toString() {
		return "Interaction{" +
			"interactionId=" + interactionId +
			", applicationName='" + applicationName + "\'" +
			", interfaceName='" + interfaceName + "\'" +
			", methodName='" + methodName + "\'" +
			", interactionDate=" + interactionDate +
			", systemMessage='" + systemMessage + '\'' +
			", userMessage='" + userMessage + '\'' +
			", result='" + result + '\'' +
			'}';
	}

	public static class Builder {
		private UUID interactionId;
		private String applicationName;
		private String interfaceName;
		private String methodName;
		private Instant interactionDate;
		private String systemMessage;
		private String userMessage;
		private String result;
		private List<InteractionScore> scores = new ArrayList<>();

		private Builder() {
		}

		private Builder(Interaction interaction) {
			this.interactionId = interaction.interactionId;
			this.applicationName = interaction.applicationName;
			this.interactionDate = interaction.interactionDate;
			this.systemMessage = interaction.systemMessage;
			this.userMessage = interaction.userMessage;
			this.result = interaction.result;
			this.scores.addAll(interaction.scores);
		}

		public Builder interactionId(UUID interactionId) {
			this.interactionId = interactionId;
			return this;
		}

		public Builder applicationName(String applicationName) {
			this.applicationName = applicationName;
			return this;
		}

		public Builder interfaceName(String interfaceName) {
			this.interfaceName = interfaceName;
			return this;
		}

		public Builder methodName(String methodName) {
			this.methodName = methodName;
			return this;
		}

		public Builder interactionDate(Instant interactionDate) {
			this.interactionDate = interactionDate;
			return this;
		}

		public Builder systemMessage(String systemMessage) {
			this.systemMessage = systemMessage;
			return this;
		}

		public Builder userMessage(String userMessage) {
			this.userMessage = userMessage;
			return this;
		}

		public Builder result(String result) {
			this.result = result;
			return this;
		}

		public Builder score(InteractionScore score) {
			if (score != null) {
				this.scores.add(score);
			}

			return this;
		}

		public Builder clearScores() {
			this.scores.clear();
			return this;
		}

		public Interaction build() {
			return new Interaction(this);
		}
	}
}
