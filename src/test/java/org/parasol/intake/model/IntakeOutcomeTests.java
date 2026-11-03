package org.parasol.intake.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonSubTypes;

class IntakeOutcomeTests {
	// Jackson only reads back a variant listed in @JsonSubTypes. A variant missing from it compiles, writes, and then fails
	// when Flow reads the checkpoint back, so the list is checked against the sealed hierarchy itself
	@Test
	void everyVariantHasItsOwnJsonSubtypeName() {
		var subTypes = IntakeOutcome.class.getAnnotation(JsonSubTypes.class).value();
		var jsonSubtypeClasses = Stream.of(subTypes)
			.<Class<?>>map(JsonSubTypes.Type::value)
			.toList();

		assertThat(jsonSubtypeClasses)
			.containsExactlyInAnyOrderElementsOf(List.of(IntakeOutcome.class.getPermittedSubclasses()));
		assertThat(Stream.of(subTypes).map(JsonSubTypes.Type::name))
			.doesNotHaveDuplicates()
			.allSatisfy(name -> assertThat(name).isNotBlank());
	}
}
