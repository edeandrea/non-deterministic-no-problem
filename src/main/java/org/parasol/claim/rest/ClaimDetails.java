package org.parasol.claim.rest;

import java.time.LocalDate;
import java.time.LocalTime;

import org.parasol.claim.model.ClaimCategory;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * A claim as returned by {@code GET /api/db/claims} and {@code GET /api/db/claims/{id}}. JSON is snake_case; null
 * values (e.g. a claim without an {@code incident_time}) are left out by the global {@code non-empty} inclusion.
 * <p>
 * This is the API contract: a column added to the {@code Claim} entity isn't exposed until it's added here.
 *
 * @param id the claim id, used in REST paths and UI routes
 * @param claimNumber the generated claim number, e.g. {@code CLM01000000}
 * @param category serialized as its display label, e.g. {@code "Single vehicle"}
 * @param policyNumber the policy number
 * @param inceptionDate the policy inception date ({@code YYYY-MM-DD})
 * @param clientName the client's name
 * @param subject the original email subject
 * @param body the original email body
 * @param summary the AI-written summary
 * @param location where the incident happened
 * @param incidentDate the incident date ({@code YYYY-MM-DD})
 * @param incidentTime the incident time ({@code HH:mm:ss}), if known
 * @param sentiment the AI-assessed customer sentiment
 * @param emailAddress the client's email address
 * @param status the claim status, e.g. {@code In Process}
 */
@JsonNaming(SnakeCaseStrategy.class)
public record ClaimDetails(
	long id,
	String claimNumber,
	ClaimCategory category,
	String policyNumber,
	LocalDate inceptionDate,
	String clientName,
	String subject,
	String body,
	String summary,
	String location,
	LocalDate incidentDate,
	LocalTime incidentTime,
	String sentiment,
	String emailAddress,
	String status
) {
}