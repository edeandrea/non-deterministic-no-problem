package ai.scoring.langfuse.evaluation;

import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import jakarta.enterprise.inject.spi.CDI;

import com.langfuse.api.LangfuseApi;
import com.langfuse.api.datasetItems.DatasetItemsApi.APIDatasetItemsListRequest;
import com.langfuse.api.model.DatasetItem;
import com.langfuse.api.model.DatasetStatus;
import com.langfuse.api.model.PaginatedDatasetItems;
import io.quarkiverse.langchain4j.testing.evaluation.EvaluationSample;
import io.quarkiverse.langchain4j.testing.evaluation.Parameters;
import io.quarkiverse.langchain4j.testing.evaluation.SampleLoadException;
import io.quarkiverse.langchain4j.testing.evaluation.SampleLoader;
import io.quarkiverse.langchain4j.testing.evaluation.Samples;
import io.quarkiverse.langfuse.client.LangfuseNotFoundException;

public class LangfuseDatasetSampleLoader implements SampleLoader<String> {
	// Helper to get CDI instance when created via ServiceLoader
  private static LangfuseApi getLangfuseApi() {
    return CDI.current().select(LangfuseApi.class).get();
  }

	@Override
	public boolean supports(String source) {
//		return true;
		return Optional.ofNullable(source)
		               .map(String::trim)
		               .map(datasetName -> getDatasetItems(datasetName).count())
		               .orElse(0L) > 0;
	}

	@Override
	public Samples<String> load(String datasetName, Class<String> outputType) throws SampleLoadException {
		return new Samples(
			getDatasetItems(datasetName)
				.map(this::toEvaluationSample)
				.toList()
		);
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

	private Stream<DatasetItem> getDatasetItems(String datasetName) {
		try {
			var firstPage = fetchPage(datasetName, 1);
			var totalPages = firstPage.getMeta().getTotalPages();

			return Stream.concat(
				getActiveDatasetItems(firstPage),
				IntStream.rangeClosed(2, totalPages)
					.mapToObj(page -> fetchPage(datasetName, page))
					.flatMap(LangfuseDatasetSampleLoader::getActiveDatasetItems)
			);
		}
		catch (LangfuseNotFoundException ex) {
			return Stream.empty();
		}
	}

	private PaginatedDatasetItems fetchPage(String datasetName, int page) {
		return getLangfuseApi()
			.datasetItems()
			.datasetItemsList(buildGetDatasetItemsRequest(datasetName, page));
	}

	private static Stream<DatasetItem> getActiveDatasetItems(PaginatedDatasetItems datasetItems) {
		return datasetItems.getData()
			.stream()
			.filter(item -> item.getStatus() == DatasetStatus.ACTIVE);
	}

	private static APIDatasetItemsListRequest buildGetDatasetItemsRequest(String datasetName, int page) {
		return APIDatasetItemsListRequest.newBuilder()
			.datasetName(datasetName)
			.limit(100)
			.page(page)
			.build();
	}
}
