package org.parasol.intake.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClaimReferenceTests {
	@Test
	void theSubjectWinsOverTheBody() {
		assertThat(ClaimReference.findClaimNumber("[CLM01000000] Re: Car accident", "About CLM01001009"))
			.contains("CLM01000000");
	}

	@Test
	void theBodyIsUsedWhenTheSubjectNamesNone() {
		assertThat(ClaimReference.findClaimNumber("Car accident", "My claim is clm01001009, please check"))
			.contains("CLM01001009");
	}

	@Test
	void bracketsAreOptionalAndOtherNumbersAreIgnored() {
		assertThat(ClaimReference.findClaimNumber("CLM01000000", "")).contains("CLM01000000");
		assertThat(ClaimReference.findClaimNumber("Policy AC-987654321", "CLM123 and CLM0100000099")).isEmpty();
	}

	@Test
	void missingTextIsFine() {
		assertThat(ClaimReference.findClaimNumber(null, null)).isEmpty();
	}
}
