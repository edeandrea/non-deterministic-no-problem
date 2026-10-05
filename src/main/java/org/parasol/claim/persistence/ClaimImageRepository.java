package org.parasol.claim.persistence;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.Tuple;
import jakarta.transaction.Transactional;

import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimImage;
import org.parasol.claim.model.ClaimImageKind;

import io.quarkus.hibernate.orm.panache.PanacheRepository;

@ApplicationScoped
@Transactional
public class ClaimImageRepository implements PanacheRepository<ClaimImage> {
	public boolean claimExists(long claimId) {
		return Claim.<Claim>findByIdOptional(claimId).isPresent();
	}

	public Optional<List<ClaimImageSummary>> listForClaim(long claimId) {
		if (Claim.<Claim>findByIdOptional(claimId).isEmpty()) {
			return Optional.empty();
		}

		return Optional.of(getEntityManager()
			.createQuery("""
				select image.id as id, image.kind as kind, image.fileName as fileName, image.contentType as contentType
				from ClaimImage image
				where image.claim.id = :claimId
				order by image.createdAt, image.id
				""", Tuple.class)
			.setParameter("claimId", claimId)
			.getResultList()
			.stream()
			.map(image -> new ClaimImageSummary(
				image.get("id", Long.class),
				image.get("kind", ClaimImageKind.class),
				image.get("fileName", String.class),
				image.get("contentType", String.class)
			))
			.toList());
	}

	public Optional<ClaimImageContent> findContentForClaim(long claimId, long imageId) {
		return find("id = ?1 and claim.id = ?2", imageId, claimId)
			.firstResultOptional()
			.map(image -> new ClaimImageContent(image.data, image.contentType));
	}

	public ClaimImage storeImage(Claim claim, ClaimImageKind kind, String fileName, String contentType, byte[] data) {
		var image = new ClaimImage();
		image.claim = claim;
		image.kind = kind;
		image.fileName = fileName;
		image.contentType = contentType;
		image.data = data.clone();
		persistAndFlush(image);

		return image;
	}

	public SeedResult storeSeedImageIfAbsent(long claimId, ClaimImageKind kind, String fileName, String contentType, byte[] data) {
		var claim = Claim.<Claim>findByIdOptional(claimId);
		if (claim.isEmpty()) {
			return SeedResult.CLAIM_NOT_FOUND;
		}

		if (count("claim.id = ?1 and fileName = ?2 and kind = ?3", claimId, fileName, kind) > 0) {
			return SeedResult.ALREADY_PRESENT;
		}

		storeImage(claim.orElseThrow(), kind, fileName, contentType, data);

		return SeedResult.CREATED;
	}

	public enum SeedResult {
		CLAIM_NOT_FOUND,
		ALREADY_PRESENT,
		CREATED
	}

	public record ClaimImageSummary(long id, ClaimImageKind kind, String fileName, String contentType) {
	}

	public record ClaimImageContent(byte[] data, String contentType) {
	}
}
