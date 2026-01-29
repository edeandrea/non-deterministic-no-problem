package ai.scoring.domain.interaction;

import java.time.Instant;
import java.util.Optional;

public record InteractionQuery(String applicationName, String interfaceName, String methodName, Instant start, Instant end) {
	private InteractionQuery(Builder builder) {
		this(builder.applicationName, builder.interfaceName, builder.methodName, builder.start, builder.end);
	}

	public static Builder builder() {
		return new Builder();
	}

	public Builder toBuilder() {
		return new Builder(this);
	}

	public Optional<String> getApplicationName() {
		return Optional.ofNullable(applicationName);
	}

	public Optional<String> getInterfaceName() {
		return Optional.ofNullable(interfaceName);
	}

	public Optional<String> getMethodName() {
		return Optional.ofNullable(methodName);
	}

	public Optional<Instant> getStart() {
		return Optional.ofNullable(start);
	}

	public Optional<Instant> getEnd() {
		return Optional.ofNullable(end);
	}

	public static class Builder {
		private String applicationName;
		private String interfaceName;
		private String methodName;
		private Instant start;
		private Instant end;

		private Builder() {}

		private Builder(InteractionQuery source) {
			this.applicationName = source.applicationName;
			this.interfaceName = source.interfaceName;
			this.methodName = source.methodName;
			this.start = source.start;
			this.end = source.end;
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

		public Builder start(Instant start) {
			this.start = start;
			return this;
		}

		public Builder end(Instant end) {
			this.end = end;
			return this;
		}

		public InteractionQuery build() {
			return new InteractionQuery(this);
		}
	}
}
