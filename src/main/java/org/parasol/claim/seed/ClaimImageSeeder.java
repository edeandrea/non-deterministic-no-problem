package org.parasol.claim.seed;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;

import org.parasol.claim.model.ClaimImage;
import org.parasol.claim.model.ClaimImageKind;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class ClaimImageSeeder {
	private static final List<SeedImage> SEED_IMAGES = List.of(
		new SeedImage(1L, "original_car1.jpg", ClaimImageKind.ORIGINAL),
		new SeedImage(1L, "car1-processed.jpg", ClaimImageKind.PROCESSED),
		new SeedImage(2L, "original_car2.jpg", ClaimImageKind.ORIGINAL),
		new SeedImage(2L, "car2-processed.jpg", ClaimImageKind.PROCESSED),
		new SeedImage(3L, "original_car3.jpg", ClaimImageKind.ORIGINAL),
		new SeedImage(3L, "car3-processed.jpg", ClaimImageKind.PROCESSED),
		new SeedImage(4L, "original_car4.jpg", ClaimImageKind.ORIGINAL),
		new SeedImage(4L, "car4-processed.jpg", ClaimImageKind.PROCESSED),
		new SeedImage(5L, "original_car5.jpg", ClaimImageKind.ORIGINAL),
		new SeedImage(5L, "car5-processed.jpg", ClaimImageKind.PROCESSED),
		new SeedImage(6L, "original_car6.jpg", ClaimImageKind.ORIGINAL),
		new SeedImage(6L, "car6-processed.jpg", ClaimImageKind.PROCESSED)
	);

	@Transactional
	void onStart(@Observes StartupEvent event) {
		seedImages();
	}

	void seedImages() {
		for (var seedImage : SEED_IMAGES) {
			seedImage(seedImage);
		}
	}

	void seedImage(SeedImage seedImage) {
		if (!ClaimImage.claimExists(seedImage.claimId())) {
			Log.warnf("Skipping image %s because claim id %d was not found", seedImage.fileName(), seedImage.claimId());
			return;
		}

		var result = ClaimImage.storeSeedImageIfAbsent(
			seedImage.claimId(),
			seedImage.kind(),
			seedImage.fileName(),
			"image/jpeg",
			readImage(seedImage.fileName())
		);

		if (result == ClaimImage.SeedResult.CREATED) {
			Log.infof("Seeded %s image %s for claim id %d", seedImage.kind(), seedImage.fileName(), seedImage.claimId());
		}
		else if (result == ClaimImage.SeedResult.CLAIM_NOT_FOUND) {
			Log.warnf("Skipping image %s because claim id %d was not found", seedImage.fileName(), seedImage.claimId());
		}
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

	record SeedImage(long claimId, String fileName, ClaimImageKind kind) {
	}
}
