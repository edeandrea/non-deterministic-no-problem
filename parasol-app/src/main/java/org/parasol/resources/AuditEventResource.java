package org.parasol.resources;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import org.parasol.model.audit.AuditEvent;
import org.parasol.model.audit.AuditStats;
import org.parasol.model.audit.Interactions;
import org.parasol.repository.AuditEventRepository;

import io.quarkus.panache.common.Sort;

@Path("/auditEvents")
@Produces(MediaType.APPLICATION_JSON)
public class AuditEventResource {
	private final AuditEventRepository auditEventRepository;

	public AuditEventResource(AuditEventRepository auditEventRepository) {
		this.auditEventRepository = auditEventRepository;
	}

	@GET
	public List<AuditEvent> getAllAuditEvents() {
		return this.auditEventRepository.listAll(Sort.by("createdOn"));
	}

	@GET
	@Path("/{interactionId}")
	public List<AuditEvent> getEventsForInteraction(@PathParam("interactionId") UUID interactionId) {
		return this.auditEventRepository.getAllForInteractionId(interactionId);
	}

	@GET
	@Path("/stats")
	public AuditStats getStats(@QueryParam("start") Optional<Instant> start, @QueryParam("end") Optional<Instant> end) {
		return this.auditEventRepository.getAuditStats(start, end);
	}

	@GET
	@Path("/interactions")
	public Interactions getLLMInteractions(@QueryParam("start") Optional<Instant> start, @QueryParam("end") Optional<Instant> end) {
		return this.auditEventRepository.getLLMInteractions(start, end);
	}
}
