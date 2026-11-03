package org.parasol.intake.agent.extraction;

import java.util.Objects;

import org.parasol.intake.model.IncidentDetails;

/**
 * Carries the details an {@link IncidentDetailsOutputGuardrail} rejection would fall back to once its reprompts run
 * out: the model's answer with the invalid date and/or time cleared, so they count as missing rather than failing the
 * run.
 * <p>
 * It's never thrown. The guardrail attaches it as its failure's cause, the only slot that survives into the
 * {@code OutputGuardrailException} raised after the last retry, and {@link ClaimExtractionWorkflow}'s error handler
 * reads it back from there.
 */
public final class InvalidIncidentDetailsException extends RuntimeException {
	private final transient IncidentDetails fallback;

	InvalidIncidentDetailsException(String message, IncidentDetails fallback) {
		// No stack trace: it's a value carrier, created on every rejected answer, and the trace would point at the guardrail
		super(message, null, false, false);
		this.fallback = Objects.requireNonNull(fallback, "fallback");
	}

	/**
	 * The details to use if the model never corrects its answer.
	 *
	 * @return the model's answer with the invalid date and/or time cleared
	 */
	public IncidentDetails fallbackDetails() {
		return this.fallback;
	}
}
