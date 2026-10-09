package org.parasol.intake.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class MatchedClaimTests {
	@Test
	void onlyThePendingStatusesArePendingIgnoringCase() {
		assertThat(MatchedClaim.of("CLM01000000", "Pending Information").isPending()).isTrue();
		assertThat(MatchedClaim.of("CLM01000000", "PENDING REVIEW").isPending()).isTrue();
		assertThat(MatchedClaim.of("CLM01000000", "In Process").isPending()).isFalse();
		assertThat(MatchedClaim.none())
			.returns(false, MatchedClaim::isMatched)
			.returns(false, MatchedClaim::isPending);
	}

	@Test
	void needsBothAClaimNumberAndAStatusOrNeither() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new MatchedClaim("CLM01000000", null));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new MatchedClaim(null, "New"));
	}
}
