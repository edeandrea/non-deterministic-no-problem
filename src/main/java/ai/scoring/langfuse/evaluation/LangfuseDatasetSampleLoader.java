package ai.scoring.langfuse.evaluation;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.enterprise.inject.spi.CDI;

import org.eclipse.microprofile.config.ConfigProvider;

import com.langfuse.api.model.DatasetItem;
import com.langfuse.api.model.DatasetStatus;
import io.quarkiverse.langchain4j.testing.evaluation.EvaluationSample;
import io.quarkiverse.langchain4j.testing.evaluation.Parameters;
import io.quarkiverse.langchain4j.testing.evaluation.SampleLoadException;
import io.quarkiverse.langchain4j.testing.evaluation.SampleLoader;
import io.quarkiverse.langchain4j.testing.evaluation.Samples;
import io.quarkiverse.langfuse.api.DatasetItemFilter;
import io.quarkiverse.langfuse.api.LangfuseOperations;
import io.quarkiverse.langfuse.client.LangfuseNotFoundException;

public class LangfuseDatasetSampleLoader implements SampleLoader<String> {
	// SPIKE: the recorded input is the generation's whole prompt; these recover ClaimService.chat's ClaimBotQuery from it
	private static final Pattern CLAIM_ID = Pattern.compile("Claim ID:\\s*(\\d+)");
	private static final Pattern INCEPTION_DATE = Pattern.compile("Policy Inception Date:\\s*(\\S+)");
	private static final Pattern CLAIM = Pattern.compile("Claim Summary:\\s*(.*?)\\s*Question:", Pattern.DOTALL);
	private static final Pattern QUESTION = Pattern.compile("Question:\\s*(.*?)\\s*(?:Answer using the following information:|$)", Pattern.DOTALL);

	// Helper to get CDI instance when created via ServiceLoader
	private static LangfuseOperations getLangfuseOperations() {
		return CDI.current().select(LangfuseOperations.class).get();
	}

	@Override
	public boolean supports(String source) {
		return Optional.ofNullable(source)
			.map(String::strip)
			// datasets().findByName() throws IllegalArgumentException for blank names before issuing any request, and
			// that isn't a SampleLoadException, so it would escape DriftDetectionOutputGuardrail.validate()
			.filter(name -> !name.isBlank())
			.flatMap(getLangfuseOperations().datasets()::findByName)
			.isPresent();
	}

	@Override
	public Samples<String> load(String datasetName, Class<String> outputType) throws SampleLoadException {
		var filterBuilder = DatasetItemFilter.builder()
			.datasetName(datasetName);

		// SPIKE: pin the dataset to a Langfuse dataset version (an ISO 8601 timestamp)
		ConfigProvider.getConfig()
			.getOptionalValue("spike.dataset-version", String.class)
			.filter(version -> !version.isBlank())
			.map(OffsetDateTime::parse)
			.ifPresent(filterBuilder::version);

		try {
			var datasetItems = getLangfuseOperations()
				.datasetItems()
				.matching(filterBuilder.build())
				.streamAll()
				.filter(item -> item.getStatus() == DatasetStatus.ACTIVE)
				.map(this::toEvaluationSample)
				.toList();

			return new Samples(datasetItems);
		}
		catch (LangfuseNotFoundException _) {
			return new Samples<>();
		}
	}

	@Override
	public int priority() {
		return 100;
	}

	private EvaluationSample<String> toEvaluationSample(DatasetItem datasetItem) {
		var input = String.valueOf(datasetItem.getInput());
		var parameters = new Parameters()
			.add("input", input)
			.add("claimId", find(CLAIM_ID, input))
			.add("inceptionDate", find(INCEPTION_DATE, input))
			.add("claim", find(CLAIM, input))
			.add("query", find(QUESTION, input));

		return EvaluationSample.<String>builder()
			// SPIKE: was getDatasetId(), which gives every sample the same name
			.withName(datasetItem.getId())
			.withParameters(parameters)
			.withExpectedOutput(String.valueOf(datasetItem.getExpectedOutput()))
			.build();
	}

	private static String find(Pattern pattern, String input) {
		var matcher = pattern.matcher(input);
		return matcher.find() ? matcher.group(1).strip() : "";
	}
}
