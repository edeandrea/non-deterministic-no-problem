package org.parasol.intake;

import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A required piece of claim detail that can be missing from a customer's email or requested by a reviewer.
 */
public enum MissingItem {
	INCIDENT_DESCRIPTION("incident description"),
	INCIDENT_DATE("incident date"),
	LOCATION("location"),
	CATEGORY("category");

	private final String label;

	MissingItem(String label) {
		this.label = label;
	}

	/**
	 * The human-readable label shown in missing-information emails and review UI checklists.
	 *
	 * @return the display label, e.g. {@code "incident description"}
	 */
	@JsonValue
	public String label() {
		return this.label;
	}

	/**
	 * Finds the missing item whose label or constant name matches the given value, ignoring case and whitespace.
	 *
	 * @param value a label or constant name
	 * @return the matching item, or empty if none matches
	 */
	public static Optional<MissingItem> find(String value) {
		return Optional.ofNullable(value)
			.map(String::strip)
			.flatMap(v -> Stream.of(values())
				.filter(item -> item.label.equalsIgnoreCase(v) || item.name().equalsIgnoreCase(v))
				.findFirst());
	}

	/**
	 * Resolves a missing item from its label or constant name.
	 *
	 * @param value a label or constant name
	 * @return the matching missing item
	 * @throws IllegalArgumentException if no missing item matches
	 */
	@JsonCreator
	public static MissingItem fromValue(String value) {
		return find(value)
			.orElseThrow(() -> new IllegalArgumentException("Unknown missing item: " + value));
	}
}
