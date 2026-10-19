package org.parasol.claim.model;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.Length;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import io.quarkus.hibernate.orm.panache.PanacheEntity;

/**
 * An email the intake accepted, recorded by its {@code Message-ID}. The unique {@code Message-ID} is the duplicate check,
 * and later emails are matched to a claim through the {@code In-Reply-To} and {@code References} headers that name one.
 * <p>
 *   The claim is empty until the email creates or matches one (a not-a-claim email never has one). Deleting a claim
 *   deletes its rows.
 * </p>
 */
@Entity
@Table(name = "claim_correspondence", indexes = @Index(name = "claim_correspondence_claim_id_idx", columnList = "claim_id"))
public class ClaimCorrespondence extends PanacheEntity {
	@Column(nullable = false, unique = true, updatable = false)
	public String messageId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "claim_id")
	@OnDelete(action = OnDeleteAction.CASCADE)
	public Claim claim;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	public CorrespondenceDirection direction;

	@Column(nullable = false)
	public Instant sentDate;

	public String sender;

	@Column(length = Length.LONG32)
	public String strippedText;

	/**
	 * Finds the claim a reply belongs to from the {@code Message-ID}s in its {@code In-Reply-To} and {@code References}
	 * headers. When they name several claims, the most recently recorded one wins.
	 *
	 * @param messageIds the referenced {@code Message-ID}s
	 * @return the claim, or empty if none of them was recorded against a claim
	 */
	public static Optional<Claim> findClaimByMessageIds(Collection<String> messageIds) {
		return messageIds.isEmpty()
			? Optional.empty()
			: ClaimCorrespondence.<ClaimCorrespondence>find("claim is not null and messageId in ?1 order by id desc", messageIds)
				.firstResultOptional()
				.map(correspondence -> correspondence.claim);
	}

	/**
	 * Finds the row recorded for a {@code Message-ID}.
	 *
	 * @param messageId the {@code Message-ID}, angle brackets included
	 * @return the row, or empty if no such email was accepted
	 */
	public static Optional<ClaimCorrespondence> findByMessageId(String messageId) {
		return ClaimCorrespondence.<ClaimCorrespondence>find("messageId", messageId)
			.firstResultOptional();
	}
}
