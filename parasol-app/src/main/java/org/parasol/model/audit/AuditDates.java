package org.parasol.model.audit;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record AuditDates(Instant start, Instant end) {
	public static AuditDates from(Optional<Instant> start, Optional<Instant> end) {
		var realEnd = end.orElseGet(Instant::now);
		var realStart = start
			.filter(s -> s.isBefore(realEnd))
			.orElseGet(() -> realEnd.minus(7, ChronoUnit.DAYS));

		return new AuditDates(realStart, realEnd);
	}
}
