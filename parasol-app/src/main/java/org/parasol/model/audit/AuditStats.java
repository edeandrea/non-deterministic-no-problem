package org.parasol.model.audit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record AuditStats(AuditDates auditDates, List<InteractionStats> stats) {
	public static AuditStats empty(AuditDates auditDates) {
		return new AuditStats(auditDates, List.of());
	}

	public record InteractionStats(
		UUID interactionId,
		Instant interactionDate,
		Long numberLlmFailures,
		Long totalOutputGuardrailExecutions,
		Long totalOutputGuardrailFailures,
		BigDecimal avgOutputGuardrailExecutions,
		BigDecimal avgOutputGuardrailFailures
	) {}
}
