package org.parasol.intake.agent.extraction;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.function.Function;

/**
 * Parses the ISO date and time strings the extraction agents exchange. They stay strings in
 * {@link org.parasol.intake.model.IncidentDetails} so the agentic scope stays JSON-safe (spike Q13); these helpers are
 * where they become {@code java.time} values.
 */
final class IsoValues {
	private IsoValues() {
	}

	static Optional<LocalDate> parseDate(String value) {
		return parse(value, LocalDate::parse);
	}

	static Optional<LocalTime> parseTime(String value) {
		return parse(value, LocalTime::parse);
	}

	private static <T> Optional<T> parse(String value, Function<String, T> parser) {
		return Optional.ofNullable(value)
			.map(String::strip)
			.filter(v -> !v.isEmpty())
			.flatMap(v -> tryParse(v, parser));
	}

	// The java.time parsers signal a bad value only by throwing, so this is the one place that turns that into empty
	private static <T> Optional<T> tryParse(String value, Function<String, T> parser) {
		try {
			return Optional.of(parser.apply(value));
		}
		catch (DateTimeParseException e) {
			return Optional.empty();
		}
	}
}
