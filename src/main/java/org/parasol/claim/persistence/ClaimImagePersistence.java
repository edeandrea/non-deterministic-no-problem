package org.parasol.claim.persistence;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.transaction.Transactional;

import org.parasol.claim.model.Claim;
import org.parasol.claim.model.ClaimImage;
import org.parasol.claim.model.ClaimImageKind;

@ApplicationScoped
@Transactional
public class ClaimImagePersistence {
	@Inject
	EntityManager entityManager;

	public boolean claimExists(long claimId) {
		return entityManager.find(Claim.class, claimId) != null;
	}

	public Optional<List<ClaimImageSummary>> listForClaim(long claimId) {
		if (entityManager.find(Claim.class, claimId) == null) {
			return Optional.empty();
		}

		return Optional.of(entityManager
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
		return entityManager.createQuery("""
			select image.data as data, image.contentType as contentType
			from ClaimImage image
			where image.id = :imageId and image.claim.id = :claimId
			""", Tuple.class)
			.setParameter("imageId", imageId)
			.setParameter("claimId", claimId)
			.getResultList()
			.stream()
			.findFirst()
			.map(image -> new ClaimImageContent(image.get("data", byte[].class), image.get("contentType", String.class)));
	}

	public ClaimImage storeImage(Claim claim, ClaimImageKind kind, String fileName, String contentType, byte[] data) {
		var image = new ClaimImage();
		image.claim = claim;
		image.kind = kind;
		image.fileName = fileName;
		image.contentType = contentType;
		image.data = data.clone();
		entityManager.persist(image);
		entityManager.flush();

		return image;
	}

	public SeedResult storeSeedImageIfAbsent(long claimId, ClaimImageKind kind, String fileName, String contentType, byte[] data) {
		var claim = entityManager.find(Claim.class, claimId);
		if (claim == null) {
			return SeedResult.CLAIM_NOT_FOUND;
		}

		var existingCount = entityManager.createQuery("""
			select count(image)
			from ClaimImage image
			where image.claim.id = :claimId and image.fileName = :fileName and image.kind = :kind
			""", Long.class)
			.setParameter("claimId", claimId)
			.setParameter("fileName", fileName)
			.setParameter("kind", kind)
			.getSingleResult();
		if (existingCount > 0) {
			return SeedResult.ALREADY_PRESENT;
		}

		storeImage(claim, kind, fileName, contentType, data);

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
