package org.parasol.claim.model;

import java.time.Instant;

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

	@Column(nullable = false, columnDefinition = "bytea")
	public byte[] data;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	public Instant createdAt;
}
