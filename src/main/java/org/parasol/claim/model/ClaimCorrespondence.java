package org.parasol.claim.model;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
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
import io.quarkus.panache.common.Sort;

/**
 * An email of a claim's correspondence, recorded by its {@code Message-ID}: one the intake accepted
 * ({@link CorrespondenceDirection#INBOUND}) or a reply it sent about a claim ({@link CorrespondenceDirection#OUTBOUND}).
 * The unique {@code Message-ID} is the duplicate check, and later emails are matched to a claim through the
 * {@code In-Reply-To} and {@code References} headers that name one.
 * <p>
 *   The claim is empty until an inbound email creates or matches one (a not-a-claim email never has one). Deleting a claim
 *   deletes its rows.
 * </p>
 * <p>
 *   For an outbound row, {@link #sentDate} and {@link #receivedAt} are both the moment the mail server accepted the reply, by
 *   this app's clock, {@link #sender} is the claims address, {@link #subject} is the subject as sent (with its {@code [CLM...] Re:} prefix) and
 *   {@link #strippedText} is the text sent.
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

	/**
	 * When the mailbox received the email (IMAP INTERNALDATE). Unlike {@link #sentDate}, a sender's client can't set it.
	 */
	@Column(nullable = false)
	public Instant receivedAt;

	public String sender;

	@Column(length = Length.LONG32)
	public String subject;

	/**
	 * Whether this email created its claim. It is {@code false} for every other email, including the replies to a claim that
	 * someone else entered (e.g. an adjustor), whose original content then isn't in any email.
	 */
	@Column(nullable = false)
	public boolean createdClaim;

	@Column(length = Length.LONG32)
	public String strippedText;

	/**
	 * Records a reply the intake sent about a claim. The row is persisted in the current transaction.
	 *
	 * @param claim the claim the reply is about
	 * @param messageId the {@code Message-ID} the reply was sent with
	 * @param sender the address the reply was sent from
	 * @param subject the subject as sent
	 * @param text the text sent
	 * @return the persisted row
	 */
	public static ClaimCorrespondence recordReply(Claim claim, String messageId, String sender, String subject, String text) {
		var sentAt = Instant.now();
		var correspondence = new ClaimCorrespondence();

		correspondence.messageId = messageId;
		correspondence.claim = claim;
		correspondence.direction = CorrespondenceDirection.OUTBOUND;
		correspondence.sentDate = sentAt;
		correspondence.receivedAt = sentAt;
		correspondence.sender = sender;
		correspondence.subject = subject;
		correspondence.strippedText = text;
		correspondence.persist();

		return correspondence;
	}

	/**
	 * Lists a claim's emails in the order they happened, which is the order they were recorded: an inbound email is recorded as
	 * the intake picks it up, a reply as soon as the mail server has accepted it. Their times can't give that order, because an
	 * inbound row's {@link #receivedAt} comes from the mail server's clock and an outbound row's from this app's.
	 *
	 * @param claim the claim
	 * @return the claim's rows, oldest first; empty if the claim has none
	 */
	public static List<ClaimCorrespondence> listForClaim(Claim claim) {
		return ClaimCorrespondence.<ClaimCorrespondence>list("claim", Sort.by("id"), claim);
	}

	/**
	 * Lists a claim's emails in the order they happened, like {@link #listForClaim(Claim)}.
	 *
	 * @param claimId the claim id
	 * @return the claim's rows, oldest first; empty if the claim has none
	 * @throws ClaimNotFoundException if there's no claim with that id
	 */
	public static List<ClaimCorrespondence> listForClaim(long claimId) {
		return Claim.<Claim>findByIdOptional(claimId)
			.map(claim -> listForClaim(claim))
			.orElseThrow(() -> new ClaimNotFoundException(claimId));
	}

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
	 * Counts the inbound emails recorded against a claim: the first one and every one later linked to it.
	 *
	 * @param claim the claim
	 * @return the number of rows
	 */
	public static long countInboundForClaim(Claim claim) {
		return count("claim = ?1 and direction = ?2", claim, CorrespondenceDirection.INBOUND);
	}

	/**
	 * Finds when the claim's first inbound email was received.
	 *
	 * @param claim the claim
	 * @return the earliest {@link #receivedAt}, or empty if no email is recorded against the claim
	 */
	public static Optional<Instant> findFirstInboundReceivedAt(Claim claim) {
		return ClaimCorrespondence.<ClaimCorrespondence>find("claim = ?1 and direction = ?2 order by receivedAt", claim, CorrespondenceDirection.INBOUND)
			.firstResultOptional()
			.map(correspondence -> correspondence.receivedAt);
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
