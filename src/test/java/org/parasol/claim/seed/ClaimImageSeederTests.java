package org.parasol.claim.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimImage;
import org.parasol.claim.model.ClaimImageKind;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ClaimImageSeederTests {
	private static final List<String> SEEDED_CLAIM_NUMBERS = List.of(
		"CLM01000000",
		"CLM01001009",
		"CLM01002018",
		"CLM01003027",
		"CLM01004036",
		"CLM01005045"
	);

	@Inject
	ClaimImageSeeder claimImageSeeder;

	@Test
	void seedsOneOriginalAndOneProcessedImageForEverySeededClaim() {
		assertThat(ClaimImage.count())
			.isEqualTo(12);

		assertThat(SEEDED_CLAIM_NUMBERS)
			.allSatisfy(claimNumber -> {
				var claim = Claim.findByClaimNumber(claimNumber)
					.orElseThrow();

				assertThat(ClaimImage.<ClaimImage>list("claim.id = ?1 and kind = ?2", claim.id, ClaimImageKind.ORIGINAL))
					.hasSize(1);

				assertThat(ClaimImage.<ClaimImage>list("claim.id = ?1 and kind = ?2", claim.id, ClaimImageKind.PROCESSED))
					.hasSize(1);
			});
	}

	@Test
	void seedingAgainDoesNotCreateDuplicates() {
		var countBefore = ClaimImage.count();

		QuarkusTransaction.requiringNew().run(claimImageSeeder::seedImages);

		assertThat(ClaimImage.count())
			.isEqualTo(countBefore);
	}

	@Test
	void missingClaimIsSkipped() {
		var countBefore = ClaimImage.count();

		QuarkusTransaction.requiringNew().run(() ->
			claimImageSeeder.seedImage(new ClaimImageSeeder.SeedImage("CLM99999999", "missing.jpg", ClaimImageKind.ORIGINAL))
		);

		assertThat(ClaimImage.count())
			.isEqualTo(countBefore);
	}
}
