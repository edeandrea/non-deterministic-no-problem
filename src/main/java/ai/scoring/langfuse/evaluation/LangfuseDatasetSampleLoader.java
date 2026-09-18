package ai.scoring.langfuse.evaluation;

import java.util.Optional;

import jakarta.enterprise.inject.spi.CDI;

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
		var datasetItemFilter = DatasetItemFilter.builder()
			.datasetName(datasetName)
			.build();

		try {
			var datasetItems = getLangfuseOperations()
				.datasetItems()
				.matching(datasetItemFilter)
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
		return EvaluationSample.<String>builder()
			.withName(datasetItem.getDatasetId())
			.withParameters(new Parameters().add("input", datasetItem.getInput()))
			.withExpectedOutput(String.valueOf(datasetItem.getExpectedOutput()))
			.build();
	}
}
