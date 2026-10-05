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
	private static final List<Long> SEEDED_CLAIM_IDS = List.of(1L, 2L, 3L, 4L, 5L, 6L);

	@Inject
	ClaimImageSeeder claimImageSeeder;

	@Test
	void seedsOneOriginalAndOneProcessedImageForEverySeededClaim() {
		assertThat(ClaimImage.count())
			.isEqualTo(12);

		assertThat(SEEDED_CLAIM_IDS)
			.allSatisfy(claimId -> {
				var claim = Claim.<Claim>findByIdOptional(claimId)
					.map(seedClaim -> Claim.findByClaimNumber(seedClaim.claimNumber).orElseThrow())
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
