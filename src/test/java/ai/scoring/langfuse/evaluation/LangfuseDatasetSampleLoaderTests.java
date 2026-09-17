package ai.scoring.langfuse.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import jakarta.inject.Inject;

import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import ai.scoring.langfuse.evaluation.LangfuseDatasetSampleLoaderTests.KeysTestProfile;
import com.langfuse.api.datasetItems.DatasetItemsApi.APIDatasetItemsCreateRequest;
import com.langfuse.api.model.CreateDatasetItemRequest;
import com.langfuse.api.model.CreateDatasetRequest;
import io.quarkiverse.langfuse.api.LangfuseOperations;
import io.smallrye.mutiny.infrastructure.Infrastructure;

@QuarkusTest
@TestProfile(KeysTestProfile.class)
@TestMethodOrder(OrderAnnotation.class)
class LangfuseDatasetSampleLoaderTests {
	@Inject
	LangfuseOperations langfuse;

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
		this.langfuse.datasets().createIfAbsent(
			CreateDatasetRequest.builder()
				.name("dataset1")
				.build());

		assertThat(this.langfuseDatasetSampleLoader.supports("datasetX"))
			.isFalse();
	}

	@Test
	@Order(2)
	void emptyDataset() {
		assertThat(this.langfuseDatasetSampleLoader.supports("dataset1"))
			.isTrue();

		assertThat(this.langfuseDatasetSampleLoader.load("dataset1", String.class))
			.isNotNull()
			.isEmpty();
	}

	@Test
	@Order(3)
	void datasetFound() {
		// Create dataset
		this.langfuse.datasets().createIfAbsent(
			CreateDatasetRequest.builder()
				.name("dataset2")
				.build());

		// Add items to dataset
		this.langfuse.api().datasetItems().datasetItemsCreate(
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
	void datasetPaginationWorks() throws InterruptedException {
		// Create dataset
		this.langfuse.datasets().createIfAbsent(
			CreateDatasetRequest.builder()
				.name("dataset3")
				.build());

		var items = 520;
		var latch = new CountDownLatch(items);

		// Add items to dataset
		IntStream.range(0, items)
			.forEach(i ->
				Infrastructure.getDefaultExecutor().execute(() -> {
						this.langfuse.api()
						             .datasetItems()
						             .datasetItemsCreate(APIDatasetItemsCreateRequest.newBuilder()
						                                                                .createDatasetItemRequest(CreateDatasetItemRequest.builder()
						                                                                                                                  .datasetName("dataset3")
						                                                                                                                  .input("intput")
						                                                                                                                  .expectedOutput("output")
						                                                                                                                  .sourceTraceId("1234")
						                                                                                                                  .build())
						                                                                .build());

						latch.countDown();
					}
					)
			);

		assertThat(this.langfuseDatasetSampleLoader.supports("dataset3"))
			.isTrue();

		assertThat(latch.await(30, TimeUnit.SECONDS))
			.as("All %d dataset items should be created", items)
			.isTrue();

		assertThat(this.langfuseDatasetSampleLoader.load("dataset3", String.class))
			.isNotNull()
			.hasSize(items);
	}

	public static class KeysTestProfile implements QuarkusTestProfile {
		@Override
		public Map<String, String> getConfigOverrides() {
			return Map.of(
				"quarkus.langchain4j.openai.api-key", "changeme",
				"quarkus.langchain4j.openai.session-sentiment.api-key", "changeme",
				"quarkus.langchain4j.openai.judge.api-key", "changeme"
			);
		}
	}
}
