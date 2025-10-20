package org.parasol.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;

import org.parasol.ai.audit.AuditObserved;
import org.parasol.mapping.AuditEventMapper;
import org.parasol.model.audit.AuditDates;
import org.parasol.model.audit.AuditEvent;
import org.parasol.model.audit.AuditStats;
import org.parasol.model.audit.AuditStats.InteractionStats;
import org.parasol.model.audit.Interactions;
import org.parasol.model.audit.Interactions.Interaction;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.logging.Log;
import io.quarkus.panache.common.Sort;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.observability.api.event.AiServiceCompletedEvent;
import dev.langchain4j.observability.api.event.AiServiceErrorEvent;
import dev.langchain4j.observability.api.event.AiServiceResponseReceivedEvent;
import dev.langchain4j.observability.api.event.AiServiceStartedEvent;
import dev.langchain4j.observability.api.event.InputGuardrailExecutedEvent;
import dev.langchain4j.observability.api.event.OutputGuardrailExecutedEvent;
import dev.langchain4j.observability.api.event.ToolExecutedEvent;

@ApplicationScoped
public class AuditEventRepository implements PanacheRepository<AuditEvent> {
	private static final String STATS_NATIVE_QUERY = """
		WITH per_interaction AS (
      SELECT
        interaction_id,
        MIN(created_on) AS interaction_date,
        COUNT(*) FILTER (WHERE event_type = 'SERVICE_ERROR') AS num_llm_failures,
        COUNT(*) FILTER (WHERE event_type = 'OUTPUT_GUARDRAIL_EXECUTED') AS total_output_guardrail_executions,
        COUNT(*) FILTER (WHERE event_type = 'OUTPUT_GUARDRAIL_EXECUTED' AND guardrail_result IN ('FATAL', 'FAILURE')) AS total_output_guardrail_failures
      FROM audit_events 
      GROUP BY interaction_id
		)
		SELECT
      interaction_id,
      interaction_date,
		  num_llm_failures,
		  total_output_guardrail_executions,
		  total_output_guardrail_failures,
		  CASE
		    WHEN total_output_guardrail_executions > 0
		      THEN AVG(total_output_guardrail_executions) FILTER (WHERE total_output_guardrail_failures > 0) OVER ()
		    ELSE 0
		  END AS avg_output_guardrail_executions,
			CASE
		    WHEN total_output_guardrail_failures > 0
		      THEN AVG(total_output_guardrail_failures) FILTER (WHERE total_output_guardrail_failures > 0) OVER ()
		    ELSE 0
		  END AS avg_output_guardrail_failures
		FROM per_interaction
		WHERE interaction_date BETWEEN :start_date AND :end_date
		ORDER BY interaction_date
		""";

	private static final String INTERACTIONS_NATIVE_QUERY = """
		WITH per_interaction AS (
			SELECT
				interaction_id,
				MIN(created_on) AS interaction_date,
				MAX(system_message) FILTER (WHERE event_type = 'SERVICE_STARTED') AS system_message,
				MAX(user_message) FILTER (WHERE event_type = 'SERVICE_STARTED') AS user_message,
				MAX(result) FILTER (WHERE event_type = 'SERVICE_COMPLETED') AS result,
				MAX(error_message) FILTER (WHERE event_type = 'SERVICE_ERROR') AS error_message,
				MAX(cause_error_message) FILTER (WHERE event_type = 'SERVICE_ERROR') AS cause_error_message
			FROM audit_events
			GROUP BY interaction_id
		)
		SELECT
			interaction_id,
			interaction_date,
			system_message,
			user_message,
			result,
			error_message,
			cause_error_message
		FROM per_interaction
		WHERE interaction_date BETWEEN :start_date AND :end_date
		ORDER BY interaction_date
		""";

	private final AuditEventMapper auditEventMapper;

	public AuditEventRepository(AuditEventMapper auditEventMapper) {
		this.auditEventMapper = auditEventMapper;
	}

	public List<AuditEvent> getAllForInteractionId(UUID interactionId) {
		return find("invocationContext.interactionId", Sort.by("createdOn"), interactionId).list();
	}

	public AuditStats getAuditStats(Optional<Instant> start, Optional<Instant> end) {
		var auditDates = AuditDates.from(start, end);
		var stats = getEntityManager().createNativeQuery(STATS_NATIVE_QUERY, InteractionStats.class)
			                  .setParameter("start_date", auditDates.start())
			                  .setParameter("end_date", auditDates.end())
			                  .getResultList();

		return new AuditStats(auditDates, stats);
	}

	public Interactions getLLMInteractions(Optional<Instant> start, Optional<Instant> end) {
		var auditDates = AuditDates.from(start, end);
		var interactions = getEntityManager().createNativeQuery(INTERACTIONS_NATIVE_QUERY, Interaction.class)
			.setParameter("start_date", auditDates.start())
			                  .setParameter("end_date", auditDates.end())
			                  .getResultList();

		return new Interactions(auditDates, interactions);
	}

	@Transactional
	@AuditObserved(
		name = "parasol.llm.interaction.started",
		description = "A count of LLM services started",
		unit = "service interactions started"
	)
	public void serviceStarted(@Observes AiServiceStartedEvent e) {
		Log.infof(
			"LLM service started event:\ncontext: %s\nsystemMessage: %s\nuserMessage: %s",
			e.invocationContext(),
			e.systemMessage().map(SystemMessage::text).orElse(""),
			e.userMessage().singleText()
		);

		persist(this.auditEventMapper.toAuditEvent(e));
	}

	@Transactional
	@AuditObserved(
		name = "parasol.llm.interaction.completed",
		description = "A count of LLM interactions completed",
		unit = "completed interactions"
	)
	public void serviceCompleted(@Observes AiServiceCompletedEvent e) {
		Log.infof(
			"LLM interaction complete:\ncontext: %s\nresult: %s",
			e.invocationContext(),
			e.result()
		);

		persist(this.auditEventMapper.toAuditEvent(e));
	}

	@Transactional
	@AuditObserved(
		name = "parasol.llm.interaction.failed",
		description = "A count of LLM interactions failed",
		unit = "failed interactions"
	)
	public void serviceFailed(@Observes AiServiceErrorEvent e) {
		Log.infof(
			"LLM interaction failed:\ncontext: %s\nfailure: %s",
			e.invocationContext(),
			e.error().getMessage()
		);

		persist(this.auditEventMapper.toAuditEvent(e));
	}

	@Transactional
	@AuditObserved(
		name = "parasol.llm.response.received",
		description = "A count of LLM responses received",
		unit = "received responses"
	)
	public void responseReceived(@Observes AiServiceResponseReceivedEvent e) {
		Log.infof(
			"Response from LLM received:\ncontext: %s\nresponse: %s",
			e.invocationContext(),
			e.response().aiMessage().text()
		);

		persist(this.auditEventMapper.toAuditEvent(e));
	}

	@Transactional
	@AuditObserved(
		name = "parasol.llm.tool.executed",
		description = "A count of tools executed",
		unit = "executed tools"
	)
	public void toolExecuted(@Observes ToolExecutedEvent e) {
		Log.infof(
			"Tool executed:\ncontext: %s\nrequest: %s(%s)\nresult: %s",
			e.invocationContext(),
			e.request().name(),
			e.request().arguments(),
			e.resultText()
		);

		persist(this.auditEventMapper.toAuditEvent(e));
	}

	@Transactional
	@AuditObserved(
		name = "parasol.llm.guardrail.input.executed",
		description = "A count of input guardrails executed",
		unit = "executed input guardrails"
	)
	public void inputGuardrailExecuted(@Observes InputGuardrailExecutedEvent e) {
		Log.infof(
			"Input guardrail executed:\nuserMessage: %s\nresult: %s",
			e.rewrittenUserMessage().singleText(),
			e.result().result()
		);

		persist(this.auditEventMapper.toAuditEvent(e));
	}

	@Transactional
	@AuditObserved(
		name = "parasol.llm.guardrail.output.executed",
		description = "A count of output guardrails executed",
		unit = "executed output guardrails"
	)
	public void outputGuardrailExecuted(@Observes OutputGuardrailExecutedEvent e) {
		Log.infof("Output guardrail executed:\nresponseFromLLM:%s\nresult: %s",
			e.request().responseFromLLM().aiMessage().text(),
			e.result().result()
		);

		persist(this.auditEventMapper.toAuditEvent(e));
	}
}
