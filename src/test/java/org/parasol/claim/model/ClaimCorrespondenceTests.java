package org.parasol.claim.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

// @TestTransaction rolls back everything a test creates (ClaimsListPageTests expects exactly the 6 seeded claims)
@QuarkusTest
class ClaimCorrespondenceTests {
	// The column keeps microseconds and a Linux clock ticks in nanoseconds, so a reply dated with the raw clock would come back
	// from the database with a different date than the row recordReply returned
	@Test
	@TestTransaction
	void aRecordedReplyHoldsTheDatesTheDatabaseStored() {
		var recorded = ClaimCorrespondence.recordReply(newClaim(), "<out-dates@parasol.com>", "claims@parasol.com", "Subject", "Text");
		var dates = new Dates(recorded.sentDate, recorded.receivedAt);

		ClaimCorrespondence.flush();
		ClaimCorrespondence.getEntityManager().clear();
		var stored = ClaimCorrespondence.<ClaimCorrespondence>findById(recorded.id);

		assertThat(new Dates(stored.sentDate, stored.receivedAt))
			.isEqualTo(dates);
	}

	private record Dates(Instant sentDate, Instant receivedAt) {
	}

	private static Claim newClaim() {
		var claim = new Claim();
		claim.category = ClaimCategory.OTHER;
		claim.inceptionDate = LocalDate.of(2020, 1, 1);
		claim.incidentDate = LocalDate.of(2024, 1, 1);
		claim.persistAndFlush();

		return claim;
	}
}