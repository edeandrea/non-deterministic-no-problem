package org.parasol.intake.observability;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.model.decision.listener.DecisionModelErrorContext;
import dev.langchain4j.model.decision.listener.DecisionModelListener;
import dev.langchain4j.model.decision.listener.DecisionModelRequestContext;
import dev.langchain4j.model.decision.listener.DecisionModelResponseContext;
import dev.langchain4j.model.decision.response.ChoiceAnswer;
import dev.langchain4j.model.decision.response.YesNoAnswer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

/**
 * #221 spike: what quarkus-langchain4j doesn't do for decision models, a span per call with {@code gen_ai.*} attributes,
 * so a decision shows up in Tempo and in Langfuse (as a generation) inside the run's trace. Questions and answers only:
 * the state (the customer's email) stays off the span.
 */
@ApplicationScoped
public class DecisionModelSpanListener implements DecisionModelListener {
	private static final String SPAN = DecisionModelSpanListener.class.getName() + ".span";
	private static final String SCOPE = DecisionModelSpanListener.class.getName() + ".scope";

	private final Tracer tracer;
	private final ObjectMapper mapper;

	public DecisionModelSpanListener(Tracer tracer, ObjectMapper mapper) {
		this.tracer = tracer;
		this.mapper = mapper;
	}

	@Override
	public void onRequest(DecisionModelRequestContext context) {
		var request = context.decisionRequest();
		var span = this.tracer.spanBuilder("decide " + request.modelName())
			.setSpanKind(SpanKind.CLIENT)
			.setAttribute("gen_ai.operation.name", "decide")
			.setAttribute("gen_ai.provider.name", context.modelProvider().name().toLowerCase())
			.setAttribute("gen_ai.request.model", request.modelName())
			.setAttribute("langfuse.observation.type", "generation")
			.setAttribute("langfuse.observation.input", json(Map.of("questions", request.questions().keySet())))
			.startSpan();
		context.attributes().put(SPAN, span);
		context.attributes().put(SCOPE, span.makeCurrent());
	}

	@Override
	public void onResponse(DecisionModelResponseContext context) {
		var response = context.decisionResponse();
		var answers = new LinkedHashMap<String, Object>();
		response.answers().forEach((name, answer) -> answers.put(name, switch (answer) {
			case ChoiceAnswer choice -> Map.of("choice", choice.value(), "probabilities", choice.probabilities());
			case YesNoAnswer yesNo -> Map.of("yes", yesNo.probability());
			case Object other -> other.toString();
		}));
		end(context.attributes(), span -> {
			span.setAttribute("gen_ai.response.model", String.valueOf(response.modelName()));
			if (response.tokenUsage() != null) {
				span.setAttribute("gen_ai.usage.input_tokens", orZero(response.tokenUsage().inputTokenCount()));
				span.setAttribute("gen_ai.usage.output_tokens", orZero(response.tokenUsage().outputTokenCount()));
			}
			span.setAttribute("langfuse.observation.output", json(answers));
		});
	}

	@Override
	public void onError(DecisionModelErrorContext context) {
		end(context.attributes(), span -> {
			span.recordException(context.error());
			span.setStatus(StatusCode.ERROR);
		});
	}

	private static void end(Map<Object, Object> attributes, java.util.function.Consumer<Span> finish) {
		if (attributes.remove(SCOPE) instanceof Scope scope) {
			scope.close();
		}
		if (attributes.remove(SPAN) instanceof Span span) {
			finish.accept(span);
			span.end();
		}
	}

	private String json(Object value) {
		try {
			return this.mapper.writeValueAsString(value);
		}
		catch (JsonProcessingException e) {
			return String.valueOf(value);
		}
	}

	private static long orZero(Integer value) {
		return (value == null) ? 0 : value;
	}
}
