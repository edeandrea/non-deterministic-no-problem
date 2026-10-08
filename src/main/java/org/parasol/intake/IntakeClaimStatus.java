package org.parasol.intake;

import io.quarkus.qute.TemplateEnum;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Claim status values specific to or set by the email intake workflow.
 * <p>
 *   The reply templates read the labels as {@code {IntakeClaimStatus:PENDING_REVIEW.label}}, so the status a reply names
 *   is the one stored on the claim.
 * </p>
 */
@TemplateEnum
public enum IntakeClaimStatus {
	PENDING_INFORMATION("Pending Information"),
	PENDING_REVIEW("Pending Review"),
	IN_PROCESS("In Process");

	private final String label;

	IntakeClaimStatus(String label) {
		this.label = label;
	}

	/**
	 * The status string stored on the claim entity and displayed in the UI.
	 *
	 * @return the status label, e.g. {@code "Pending Review"}
	 */
	@JsonValue
	public String label() {
		return this.label;
	}
}
