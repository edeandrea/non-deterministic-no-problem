package org.parasol.spike;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.parasol.intake.agent.extraction.IncidentAssessment;
import org.parasol.intake.agent.extraction.IncidentAssessmentDecisionAgent;
import org.parasol.intake.agent.extraction.IncidentDetailsAgent;
import org.parasol.intake.agent.triage.EmailClassifierAgent;
import org.parasol.intake.agent.triage.EmailClassifierDecisionAgent;
import org.parasol.intake.model.IncidentDetails;
import org.parasol.intake.workflow.CorrespondenceText;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.model.decision.DecisionModel;
import dev.langchain4j.model.decision.response.DecisionResponse;
import dev.langchain4j.model.openai.OpenAiDecisionModel;
import dev.langchain4j.model.typesafe.TypeSafeDecisionModel;
import dev.langchain4j.service.decision.DecisionServices;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * #221 spike: runs the labeled dataset through the LLM agents and the decision models, one JSON line per email and
 * backend, into {@code spike/results/<label>-<backend>.jsonl}. {@code spike/analyze.py} scores them.
 * <p>
 * {@code -Dspike=true -Dspike.label=ollama -Dspike.backends=llm,agent,nimble,tev1,clef}; a {@code jev-*} backend (e.g.
 * {@code jev-1.14.0}) is hosted Jev and needs {@code TYPESAFE_API_KEY}; a {@code gpt-*} backend (e.g. {@code gpt-6-luna}) is the
 * OpenAI Decisions API and needs {@code OPENAI_API_KEY}
 */
@QuarkusTest
@TestProfile(SpikeHarnessProfile.class)
@EnabledIfSystemProperty(named = "spike", matches = "true")
class DecisionSpikeHarnessTests {
	private static final Path DATASET = Path.of("spike/dataset/emails.json");
	private static final Path RESULTS = Path.of("spike/results");
	private static final String HOSTED_JEV = "https://api.typesafe.ai";
	private static final String WARM_UP = "Subject: Hello\n\nMy car was stolen yesterday from Main Street in Springfield.";

	@Inject
	EmailClassifierAgent llmClassifier;

	@Inject
	IncidentDetailsAgent llmDetails;

	@Inject
	SpikeDescribedClassifier describedClassifier;

	@Inject
	EmailClassifierDecisionAgent decisionClassifier;

	@Inject
	IncidentAssessmentDecisionAgent decisionAssessor;

	@Inject
	TokenCounter tokens;

	@Inject
	ObjectMapper mapper;

	@ConfigProperty(name = "parasol.models.decision-base-url")
	String decisionBaseUrl;

	@ConfigProperty(name = "parasol.intake.limits.max-body-characters", defaultValue = "20000")
	int maxBodyCharacters;

	@Test
	void run() throws IOException {
		var emails = this.mapper.readTree(DATASET.toFile());
		var label = System.getProperty("spike.label", "run");
		var backends = Arrays.stream(System.getProperty("spike.backends", "llm,agent,nimble,tev1,clef").split(",")).map(String::strip).toList();
		var only = System.getProperty("spike.only", ".*");
		Files.createDirectories(RESULTS);

		for (var backend : backends) {
			var out = RESULTS.resolve("%s-%s.jsonl".formatted(label, backend));
			Log.infof("#221 spike: %s -> %s", backend, out);
			try (var writer = Files.newBufferedWriter(out)) {
				var runner = runnerFor(backend);
				writeLine(writer, Map.of("id", "_warmup", "warmup_ms", timed(() -> runner.classify(WARM_UP)).millis()));
				for (var email : emails) {
					if (email.get("id").asText().matches(only)) {
						writeLine(writer, runner.run(email));
					}
				}
			}
		}
	}

	private Runner runnerFor(String backend) {
		return switch (backend) {
			case "llm" -> new LlmRunner(false);
			case "llmdesc" -> new LlmRunner(true);
			case "agent" -> new AgentRunner();
			default -> new DecisionRunner(backend);
		};
	}

	private void writeLine(BufferedWriter writer, Object line) throws IOException {
		writer.write(this.mapper.writeValueAsString(line));
		writer.newLine();
		writer.flush();
	}

	private String correspondence(JsonNode email) {
		return CorrespondenceText.cap(email.get("correspondence").asText(), this.maxBodyCharacters);
	}

	private static boolean scoresCompleteness(JsonNode email) {
		var type = email.get("expected").get("email_type");
		return type.isNull() || "NEW_CLAIM".equals(type.asText());
	}

	private static String requiredEnv(String name) {
		var value = System.getenv(name);
		if ((value == null) || value.isBlank()) {
			throw new IllegalStateException(name + " must be set to run a hosted Jev backend");
		}
		return value;
	}

	private static <T> Timed<T> timed(Supplier<T> call) {
		var start = System.nanoTime();
		try {
			return new Timed<>(call.get(), (System.nanoTime() - start) / 1_000_000, null);
		}
		catch (RuntimeException e) {
			Log.warnf("#221 spike call failed: %s", e.toString());
			return new Timed<>(null, (System.nanoTime() - start) / 1_000_000, rootMessage(e));
		}
	}

	private static String rootMessage(Throwable e) {
		var root = e;
		while ((root.getCause() != null) && (root.getCause() != root)) {
			root = root.getCause();
		}
		var message = "%s: %s".formatted(root.getClass().getSimpleName(), root.getMessage());
		return (message.length() > 400) ? message.substring(0, 400) : message;
	}

	private record Timed<T>(T value, long millis, String error) {
	}

	private abstract class Runner {
		abstract Object classify(String correspondence);

		abstract Map<String, Object> classification(String correspondence);

		abstract Map<String, Object> assessment(JsonNode email, String correspondence);

		Map<String, Object> run(JsonNode email) {
			var text = correspondence(email);
			var line = new LinkedHashMap<String, Object>();
			line.put("id", email.get("id").asText());
			line.put("chars", text.length());
			line.put("classification", classification(text));
			if (scoresCompleteness(email)) {
				line.put("assessment", assessment(email, text));
			}
			return line;
		}
	}

	private final class LlmRunner extends Runner {
		private final boolean described;

		LlmRunner(boolean described) {
			this.described = described;
		}

		@Override
		Object classify(String correspondence) {
			return this.described ?
			       describedClassifier.classifyEmail(correspondence) :
			       DecisionSpikeHarnessTests.this.llmClassifier.classifyEmail(correspondence);
		}

		@Override
		Map<String, Object> classification(String correspondence) {
			tokens.reset();
			var result = timed(() -> classify(correspondence));
			var line = new LinkedHashMap<String, Object>();
			line.put("value", (result.value() == null) ? null : result.value().toString());
			line.put("ms", result.millis());
			line.put("error", result.error());
			line.put("llm_calls", tokens.calls());
			line.put("input_tokens", tokens.usage().inputTokenCount());
			line.put("output_tokens", tokens.usage().outputTokenCount());
			return line;
		}

		@Override
		Map<String, Object> assessment(JsonNode email, String correspondence) {
			tokens.reset();
			var line = new LinkedHashMap<String, Object>();
			// What ClaimExtractionWorkflow's error handler does: a guardrail that never got valid dates yields its fallback
			var fallback = new java.util.concurrent.atomic.AtomicReference<IncidentDetails>();
			var result = timed(() -> {
				try {
					return llmDetails.extractIncidentDetails(correspondence, IncidentDetails.empty(), List.of(), email.get("sent_date").asText());
				}
				catch (RuntimeException e) {
					findFallback(e).ifPresentOrElse(fallback::set, () -> { throw e; });
					return null;
				}
			});
			var details = (result.value() != null) ? result.value() : fallback.get();
			line.put("fallback", fallback.get() != null);
			if (details != null) {
				line.put("described", Boolean.TRUE.equals(details.incidentDescribed()));
				line.put("dated", isIsoDate(details.incidentDate()));
				line.put("located", (details.location() != null) && !details.location().isBlank());
				line.put("category", (details.category() == null) ? null : details.category().name());
			}
			line.put("ms", result.millis());
			line.put("error", result.error());
			line.put("llm_calls", tokens.calls());
			line.put("input_tokens", tokens.usage().inputTokenCount());
			line.put("output_tokens", tokens.usage().outputTokenCount());
			return line;
		}

		private static java.util.Optional<IncidentDetails> findFallback(Throwable failure) {
			return java.util.stream.Stream.iterate(failure, java.util.Objects::nonNull, Throwable::getCause)
				.filter(dev.langchain4j.guardrail.OutputGuardrailException.class::isInstance)
				.map(dev.langchain4j.guardrail.OutputGuardrailException.class::cast)
				.map(dev.langchain4j.guardrail.OutputGuardrailException::result)
				.filter(java.util.Objects::nonNull)
				.flatMap(r -> r.<dev.langchain4j.guardrail.OutputGuardrailResult.Failure>failures().stream())
				.map(dev.langchain4j.guardrail.OutputGuardrailResult.Failure::cause)
				.filter(org.parasol.intake.agent.extraction.InvalidIncidentDetailsException.class::isInstance)
				.map(org.parasol.intake.agent.extraction.InvalidIncidentDetailsException.class::cast)
				.map(org.parasol.intake.agent.extraction.InvalidIncidentDetailsException::fallbackDetails)
				.findFirst();
		}

		private static boolean isIsoDate(String value) {
			try {
				LocalDate.parse(value);
				return true;
			}
			catch (RuntimeException e) {
				return false;
			}
		}
	}

	/**
	 * The Quarkus decision agents themselves, with the configured model: no probabilities, but checks they give the
	 * same answers as the plain decision service.
	 */
	private final class AgentRunner extends Runner {
		@Override
		Object classify(String correspondence) {
			return decisionClassifier.classifyEmail(correspondence);
		}

		@Override
		Map<String, Object> classification(String correspondence) {
			var result = timed(() -> classify(correspondence));
			var line = new LinkedHashMap<String, Object>();
			line.put("value", (result.value() == null) ? null : result.value().toString());
			line.put("ms", result.millis());
			line.put("error", result.error());
			return line;
		}

		@Override
		Map<String, Object> assessment(JsonNode email, String correspondence) {
			var result = timed(() -> decisionAssessor.assessIncident(correspondence));
			var line = new LinkedHashMap<String, Object>();
			var assessment = result.value();
			if (assessment != null) {
				line.put("described", assessment.incidentDescribed());
				line.put("dated", assessment.incidentDateStated());
				line.put("located", assessment.locationStated());
				line.put("category", assessment.category().toClaimCategory().map(Enum::name).orElse(null));
			}
			line.put("ms", result.millis());
			line.put("error", result.error());
			return line;
		}
	}

	private final class DecisionRunner extends Runner {
		private final SpikeDecisions decisions;

		DecisionRunner(String model) {
			// gpt-* is the OpenAI Decisions API (OPENAI_API_KEY); jev-* is hosted Jev (TYPESAFE_API_KEY); anything else is a
			// decision model on the configured System One server (Ollama)
			var hosted = model.startsWith("jev-");
			DecisionModel decisionModel = model.startsWith("gpt-") ?
				OpenAiDecisionModel.builder()
					.apiKey(requiredEnv("OPENAI_API_KEY"))
					.modelName(model)
					.timeout(Duration.ofMinutes(10))
					.maxRetries(0)
					.build() :
				TypeSafeDecisionModel.builder()
					.baseUrl(hosted ? HOSTED_JEV : decisionBaseUrl)
					.apiKey(hosted ? requiredEnv("TYPESAFE_API_KEY") : null)
					.modelName(model)
					.timeout(Duration.ofMinutes(10))
					.maxRetries(0)
					.build();
			this.decisions = DecisionServices.builder(SpikeDecisions.class).decisionModel(decisionModel).build();
		}

		@Override
		Object classify(String correspondence) {
			return this.decisions.classifyEmail(correspondence);
		}

		@Override
		Map<String, Object> classification(String correspondence) {
			var result = timed(() -> this.decisions.classifyEmail(correspondence));
			var line = new LinkedHashMap<String, Object>();
			if (result.value() != null) {
				var choice = result.value().content();
				line.put("value", choice.value().name());
				line.put("probabilities", choice.probabilities());
				line.put("margin", choice.margin());
				line.put("confidence", choice.confidence());
				line.put("input_tokens", result.value().tokenUsage().inputTokenCount());
				line.put("model", result.value().modelName());
			}
			line.put("ms", result.millis());
			line.put("error", result.error());
			return line;
		}

		@Override
		Map<String, Object> assessment(JsonNode email, String correspondence) {
			var result = timed(() -> this.decisions.assessIncident(correspondence));
			var line = new LinkedHashMap<String, Object>();
			if (result.value() != null) {
				IncidentAssessment assessment = result.value().content();
				DecisionResponse response = result.value().response();
				line.put("described", assessment.incidentDescribed());
				line.put("dated", assessment.incidentDateStated());
				line.put("located", assessment.locationStated());
				line.put("category", assessment.category().toClaimCategory().map(Enum::name).orElse(null));
				line.put("p_described", response.yesNo("incidentDescribed").probability());
				line.put("p_dated", response.yesNo("incidentDateStated").probability());
				line.put("p_located", response.yesNo("locationStated").probability());
				var category = response.choice("category");
				line.put("category_probabilities", category.probabilities());
				line.put("category_margin", category.margin());
				line.put("input_tokens", result.value().tokenUsage().inputTokenCount());
			}
			line.put("ms", result.millis());
			line.put("error", result.error());
			return line;
		}
	}
}
