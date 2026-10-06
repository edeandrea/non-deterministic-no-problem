package org.parasol.claim.model;

import java.time.Instant;
import java.util.List;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.hibernate.Length;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.quarkus.panache.common.Parameters;
import io.quarkus.panache.common.Sort;

/**
 * An image attached to a {@link Claim}: a customer photo ({@link ClaimImageKind#ORIGINAL}) or an annotated damage image
 * ({@link ClaimImageKind#PROCESSED}).
 * <p>
 * The image bytes are stored in a PostgreSQL {@code bytea} column and loaded lazily, so listing a claim's images doesn't
 * read them. Deleting a claim deletes its images ({@code on delete cascade}).
 */
@Entity
@Table(name = "claim_images", indexes = @Index(name = "claim_images_claim_id_idx", columnList = "claim_id"))
public class ClaimImage extends PanacheEntity {
	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "claim_id", nullable = false, updatable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	public Claim claim;

	@NotNull
	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	public ClaimImageKind kind;

	@NotBlank
	@Column(nullable = false, length = Length.LONG32)
	public String fileName;

	@NotNull
	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	public ClaimImageContentType contentType;

	/**
	 * The image bytes. {@code Length.LONG32} maps {@code byte[]} to {@code bytea} on PostgreSQL; {@code @Lob} would map it to a
	 * large-object {@code oid} instead.
	 */
	@NotEmpty
	@Basic(fetch = FetchType.LAZY)
	@Column(nullable = false, length = Length.LONG32)
	public byte[] data;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	public Instant createdAt;

	/**
	 * Lists a claim's images, oldest first. The image bytes aren't loaded.
	 *
	 * @param claimId the claim id
	 * @return the claim's images; empty if the claim has none
	 * @throws ClaimNotFoundException if there's no claim with that id
	 */
	public static List<ClaimImage> listForClaim(long claimId) {
		return Claim.<Claim>findByIdOptional(claimId)
			.map(claim -> ClaimImage.<ClaimImage>list("claim", Sort.by("createdAt").and("id"), claim))
			.orElseThrow(() -> new ClaimNotFoundException(claimId));
	}

	/**
	 * Finds an image of a claim. An image that exists but belongs to another claim isn't found.
	 *
	 * @param claimId the claim id
	 * @param imageId the image id
	 * @return the image
	 * @throws ClaimImageNotFoundException if the claim has no image with that id (including when the claim doesn't exist)
	 */
	public static ClaimImage findForClaim(long claimId, long imageId) {
		return ClaimImage.<ClaimImage>find("id = :imageId and claim.id = :claimId", Parameters.with("imageId", imageId).and("claimId", claimId))
			.firstResultOptional()
			.orElseThrow(() -> new ClaimImageNotFoundException(claimId, imageId));
	}

	/**
	 * Checks whether a claim already has an image of the given kind with the given file name.
	 *
	 * @param claim the claim
	 * @param kind the image kind
	 * @param fileName the file name
	 * @return {@code true} if such an image exists
	 */
	public static boolean hasImage(Claim claim, ClaimImageKind kind, String fileName) {
		return count("claim = :claim and kind = :kind and fileName = :fileName", Parameters.with("claim", claim).and("kind", kind).and("fileName", fileName)) > 0;
	}

	/**
	 * Stores an image for a claim. The image is persisted in the current transaction; its id is assigned straight away,
	 * and the row is written when the transaction flushes.
	 * <p>
	 * To store an image whose media type comes from outside (e.g. an email attachment), resolve it with
	 * {@link ClaimImageContentType#fromMediaType(String)} first: only allow-listed image types can be stored.
	 *
	 * @param claim the claim the image belongs to
	 * @param kind the image kind
	 * @param fileName the original file name
	 * @param contentType the image format, served back as the {@code Content-Type}
	 * @param data the image bytes; must not be empty
	 * @return the persisted image
	 * @throws jakarta.validation.ConstraintViolationException at flush, if a value is missing, blank or empty
	 */
	public static ClaimImage store(Claim claim, ClaimImageKind kind, String fileName, ClaimImageContentType contentType, byte[] data) {
		var image = new ClaimImage();
		image.claim = claim;
		image.kind = kind;
		image.fileName = fileName;
		image.contentType = contentType;
		image.data = data;
		image.persist();

		return image;
	}
}