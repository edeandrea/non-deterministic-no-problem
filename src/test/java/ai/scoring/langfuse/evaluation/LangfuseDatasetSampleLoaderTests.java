package ai.scoring.langfuse.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;

import jakarta.inject.Inject;

import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkus.test.junit.QuarkusTest;

import com.langfuse.api.LangfuseApi;
import com.langfuse.api.datasetItems.DatasetItemsApi.APIDatasetItemsCreateRequest;
import com.langfuse.api.datasets.DatasetsApi.APIDatasetsCreateRequest;
import com.langfuse.api.model.CreateDatasetItemRequest;
import com.langfuse.api.model.CreateDatasetRequest;
import io.smallrye.mutiny.infrastructure.Infrastructure;

@QuarkusTest
@TestMethodOrder(OrderAnnotation.class)
class LangfuseDatasetSampleLoaderTests {
	@Inject
	LangfuseApi langfuseApi;

	LangfuseDatasetSampleLoader langfuseDatasetSampleLoader = new LangfuseDatasetSampleLoader();

	@Test
	@Order(0)
	void noDatasets() {
		assertThat(this.langfuseDatasetSampleLoader.supports("no-dataset"))
			.isFalse();
	}

	@Test
	@Order(1)
	void datasetDoesntExist() {
		// Create dataset
		this.langfuseApi.datasets().datasetsCreate(
			APIDatasetsCreateRequest.newBuilder()
				.createDatasetRequest(
					CreateDatasetRequest.builder()
						.name("dataset1")
						.build()
				)
				.build());

		assertThat(this.langfuseDatasetSampleLoader.supports("datasetX"))
			.isFalse();
	}

	@Test
	@Order(2)
	void emptyDataset() {
		assertThat(this.langfuseDatasetSampleLoader.supports("dataset1"))
			.isFalse();
	}

	@Test
	@Order(3)
	void datasetFound() {
		// Create dataset
		this.langfuseApi.datasets().datasetsCreate(
			APIDatasetsCreateRequest.newBuilder()
				.createDatasetRequest(
					CreateDatasetRequest.builder()
						.name("dataset2")
						.build()
				)
				.build());

		// Add items to dataset
		this.langfuseApi.datasetItems().datasetItemsCreate(
			APIDatasetItemsCreateRequest.newBuilder()
				.createDatasetItemRequest(
					CreateDatasetItemRequest.builder()
						.datasetName("dataset2")
						.input("intput")
						.expectedOutput("output")
						.sourceTraceId("1234")
						.build()
				)
				.build());

		assertThat(this.langfuseDatasetSampleLoader.supports("dataset2"))
			.isTrue();

		assertThat(this.langfuseDatasetSampleLoader.load("dataset2", String.class))
			.isNotNull()
			.hasSize(1);
	}

	@Test
	@Order(4)
	void datasetPaginationWorks() {
		// Create dataset
		this.langfuseApi.datasets().datasetsCreate(
			APIDatasetsCreateRequest.newBuilder()
				.createDatasetRequest(
					CreateDatasetRequest.builder()
						.name("dataset3")
						.build()
				)
				.build());

		// Add items to dataset
		IntStream.range(0, 520)
			.forEach(i ->
				Infrastructure.getDefaultExecutor().execute(() ->
					this.langfuseApi.datasetItems().datasetItemsCreate(
						APIDatasetItemsCreateRequest.newBuilder()
							.createDatasetItemRequest(
								CreateDatasetItemRequest.builder()
									.datasetName("dataset3")
									.input("intput")
									.expectedOutput("output")
									.sourceTraceId("1234")
									.build())
							.build())
					)
			);

		assertThat(this.langfuseDatasetSampleLoader.supports("dataset3"))
			.isTrue();

		assertThat(this.langfuseDatasetSampleLoader.load("dataset3", String.class))
			.isNotNull()
			.hasSize(520);
	}
}