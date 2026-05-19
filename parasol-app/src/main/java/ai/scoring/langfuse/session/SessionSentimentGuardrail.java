package ai.scoring.langfuse.session;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.guardrails.JsonExtractorOutputGuardrail;

@ApplicationScoped
public class SessionSentimentGuardrail extends JsonExtractorOutputGuardrail<SessionSentiment> {
	public SessionSentimentGuardrail() {
		super(SessionSentiment.class);
	}
}
