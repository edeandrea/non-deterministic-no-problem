package org.parasol.claim.model;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;

import io.quarkus.hibernate.orm.panache.PanacheEntity;

@Entity
@Table(name = "claim_images")
public class ClaimImage extends PanacheEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "claim_id", nullable = false)
	public Claim claim;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	public ClaimImageKind kind;

	@Column(nullable = false)
	public String fileName;

	@Column(nullable = false)
	public String contentType;

	@Column(nullable = false)
	public byte[] data;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	public Instant createdAt;

	public static boolean claimExists(long claimId) {
		return Claim.findByIdOptional(claimId).isPresent();
	}

	public static Optional<List<ClaimImageSummary>> listForClaim(long claimId) {
		if (!claimExists(claimId)) {
			return Optional.empty();
		}

		return Optional.of(find("claim.id = ?1 order by createdAt, id", claimId)
			.project(ClaimImageSummary.class)
			.list());
	}

	public static Optional<ClaimImageContent> findContentForClaim(long claimId, long imageId) {
		return ClaimImage.<ClaimImage>find("id = ?1 and claim.id = ?2", imageId, claimId)
			.firstResultOptional()
			.map(image -> new ClaimImageContent(image.data, image.contentType));
	}

	public static ClaimImage storeImage(Claim claim, ClaimImageKind kind, String fileName, String contentType, byte[] data) {
		var image = new ClaimImage();
		image.claim = claim;
		image.kind = kind;
		image.fileName = fileName;
		image.contentType = contentType;
		image.data = data.clone();
		image.persistAndFlush();

		return image;
	}

	public static SeedResult storeSeedImageIfAbsent(long claimId, ClaimImageKind kind, String fileName, String contentType, byte[] data) {
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
