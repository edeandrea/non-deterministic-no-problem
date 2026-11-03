package org.parasol.intake.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.parasol.intake.model.ClaimResolution.Kind;

import dev.langchain4j.model.output.structured.Description;

class ClaimResolutionTests {
	private static final List<String> OFFERED = List.of("CLM01000000", "CLM01001009");

	@Test
	void anOfferedClaimIsKept() {
		var resolution = new ClaimResolution(Kind.EXISTING, "CLM01001009");

		assertThat(resolution.limitToOfferedClaims(OFFERED))
			.isEqualTo(resolution);
	}

	@Test
	void aClaimThatWasNotOfferedIsUnsure() {
		assertThat(new ClaimResolution(Kind.EXISTING, "CLM01005045").limitToOfferedClaims(OFFERED))
			.isEqualTo(new ClaimResolution(Kind.UNSURE, null));
	}

	@Test
	void aNewIncidentOrUnsureIsKept() {
		assertThat(List.of(new ClaimResolution(Kind.NEW_INCIDENT, null), new ClaimResolution(Kind.UNSURE, null)))
			.allSatisfy(resolution -> assertThat(resolution.limitToOfferedClaims(OFFERED)).isEqualTo(resolution));
	}

	@Test
	void theKindDescriptionSaysWhenToUseEveryKind() throws NoSuchFieldException {
		// The field, not the record component: @Description targets FIELD and TYPE only, so that's where javac puts it
		// and where LangChain4j's PojoOutputParser reads it
		var kindDescription = ClaimResolution.class.getDeclaredField("kind").getAnnotation(Description.class).value()[0];

		assertThat(Stream.of(Kind.values()).map(Kind::name))
			.as("a new Kind must be explained to the resolver in the kind field's @Description")
			.allSatisfy(kind -> assertThat(kindDescription).contains(kind));
	}
}
