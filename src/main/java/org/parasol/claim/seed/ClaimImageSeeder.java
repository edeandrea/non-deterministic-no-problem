package org.parasol.claim.seed;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;

import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimImage;
import org.parasol.claim.model.ClaimImageKind;
import org.parasol.claim.service.ClaimImageService;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class ClaimImageSeeder {
	private static final List<SeedImage> SEED_IMAGES = List.of(
		new SeedImage("CLM01000000", "original_car1.jpg", ClaimImageKind.ORIGINAL),
		new SeedImage("CLM01000000", "car1-processed.jpg", ClaimImageKind.PROCESSED),
		new SeedImage("CLM01001009", "original_car2.jpg", ClaimImageKind.ORIGINAL),
		new SeedImage("CLM01001009", "car2-processed.jpg", ClaimImageKind.PROCESSED),
		new SeedImage("CLM01002018", "original_car3.jpg", ClaimImageKind.ORIGINAL),
		new SeedImage("CLM01002018", "car3-processed.jpg", ClaimImageKind.PROCESSED),
		new SeedImage("CLM01003027", "original_car4.jpg", ClaimImageKind.ORIGINAL),
		new SeedImage("CLM01003027", "car4-processed.jpg", ClaimImageKind.PROCESSED),
		new SeedImage("CLM01004036", "original_car5.jpg", ClaimImageKind.ORIGINAL),
		new SeedImage("CLM01004036", "car5-processed.jpg", ClaimImageKind.PROCESSED),
		new SeedImage("CLM01005045", "original_car6.jpg", ClaimImageKind.ORIGINAL),
		new SeedImage("CLM01005045", "car6-processed.jpg", ClaimImageKind.PROCESSED)
	);

	private final ClaimImageService claimImageService;

	ClaimImageSeeder(ClaimImageService claimImageService) {
		this.claimImageService = claimImageService;
	}

	@Transactional
	void onStart(@Observes StartupEvent event) {
		seedImages();
	}

	void seedImages() {
		if (ClaimImage.count() == 0) {
			for (var seedImage : SEED_IMAGES) {
				seedImage(seedImage);
			}
		}
	}

	void seedImage(SeedImage seedImage) {
		Claim.findByClaimNumber(seedImage.claimNumber())
			.ifPresentOrElse(
				claim -> {
					var data = readImage(seedImage.fileName());
					claimImageService.storeImage(
						claim,
						seedImage.kind(),
						seedImage.fileName(),
						"image/jpeg",
						data
					);
					Log.infof("Seeded %s image %s for claim %s", seedImage.kind(), seedImage.fileName(), seedImage.claimNumber());
				},
				() -> Log.warnf("Skipping image %s because claim %s was not found", seedImage.fileName(), seedImage.claimNumber())
			);
	}

	private byte[] readImage(String fileName) {
		var resourcePath = "/seed/claim-images/%s".formatted(fileName);
		var resource = getClass().getResourceAsStream(resourcePath);
		if (resource == null) {
			throw new IllegalStateException("Seed image resource %s was not found".formatted(resourcePath));
		}

		try (InputStream stream = resource) {
			return stream.readAllBytes();
		}
		catch (IOException e) {
			throw new UncheckedIOException("Could not read seed image %s".formatted(resourcePath), e);
		}
	}

	record SeedImage(String claimNumber, String fileName, ClaimImageKind kind) {
	}
}
