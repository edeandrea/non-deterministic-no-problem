package ai.scoring.domain.sample;

import java.util.Optional;

import ai.scoring.domain.interaction.Interaction;

public record Source(String applicationName, String interfaceName, String methodName) {
	private static final String DELIMITER = "::";

	public Source {
		if (applicationName == null || interfaceName == null || methodName == null) {
			throw new IllegalArgumentException("Application name, interface name, and method name cannot be null");
		}
	}

	public static Optional<Source> from(String source) {
		// source should be in the format <APPLICATION_NAME>::<INTERFACE_NAME>::<METHOD_NAME>
		return Optional.ofNullable(source)
			.map(s -> s.split(DELIMITER))
			.filter(parts -> parts.length == 3)
			.map(parts -> new Source(parts[0], parts[1], parts[2]));
	}

	public static String asSourceString(Interaction interaction) {
		return String.join(DELIMITER, interaction.getApplicationName(), interaction.getInterfaceName(), interaction.getMethodName());
	}
}
