package org.parasol.intake.model;

import java.util.Set;

import org.parasol.claim.model.ClaimCategory;
import org.parasol.intake.MissingItem;

/**
 * The extracted incident details produced by {@code IncidentDetailsAgent}.
 * <p>
 * Date and time are stored as ISO-8601 strings ({@code "yyyy-MM-dd"} and {@code "HH:mm"}) to stay safe under
 * agentic-scope Jackson serialization (spike Q13).
 *
 * @param description What happened
 * @param incidentDate The incident date as an ISO string ({@code "yyyy-MM-dd"}), or null if unstated
 * @param incidentTime The incident time as an ISO string ({@code "HH:mm"}), if stated
 * @param location Where the incident took place, or null if unstated
 * @param category The claim category, {@code OTHER} when described but uncategorized, or null if unstated
 * @param policyNumber The policy number stated in the email, or null if unstated
 * @param answeredItems The subset of requested items that this email supplies
 */
public record IncidentDetails(
	String description,
	String incidentDate,
	String incidentTime,
	String location,
	ClaimCategory category,
	String policyNumber,
	Set<MissingItem> answeredItems) {

	public IncidentDetails {
		answeredItems = (answeredItems == null) ? Set.of() : Set.copyOf(answeredItems);
	}
}
