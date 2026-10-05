package org.parasol.claim.model;

import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The kind of incident a {@link Claim} is about.
 * <p>
 * The database stores the constant name ({@code MULTIPLE_VEHICLE}). JSON uses the display {@link #label() label}
 * ({@code "Multiple vehicle"}), which is what the UI shows and filters on.
 */
public enum ClaimCategory {
	SINGLE_VEHICLE("Single vehicle"),
	MULTIPLE_VEHICLE("Multiple vehicle"),
	THEFT("Theft"),
	OTHER("Other");

	private final String label;

	ClaimCategory(String label) {
		this.label = label;
	}

	/**
	 * The human-readable label, used as the JSON representation.
	 *
	 * @return the display label, e.g. {@code "Single vehicle"}
	 */
	@JsonValue
	public String label() {
		return this.label;
	}

	/**
	 * Finds the category whose label or constant name matches the given value, ignoring case and surrounding whitespace.
	 *
	 * @param value a label ({@code "Single vehicle"}) or a constant name ({@code "SINGLE_VEHICLE"})
	 * @return the matching category, or empty if none matches
	 */
	public static Optional<ClaimCategory> find(String value) {
		return Optional.ofNullable(value)
			.map(String::strip)
			.flatMap(v -> Stream.of(values())
				.filter(category -> category.label.equalsIgnoreCase(v) || category.name().equalsIgnoreCase(v))
				.findFirst());
	}

	/**
	 * Resolves a category from its label or constant name. Used by Jackson when deserializing.
	 *
	 * @param value a label or constant name
	 * @return the matching category
	 * @throws UnknownClaimCategoryException if no category matches
	 */
	@JsonCreator
	public static ClaimCategory fromValue(String value) {
		return find(value)
			.orElseThrow(() -> new UnknownClaimCategoryException(value));
	}
}
