package ai.scoring.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import ai.scoring.domain.interaction.Interaction;
import ai.scoring.domain.sample.Source;
import ai.scoring.repository.InteractionRepository;
import io.quarkiverse.langchain4j.testing.evaluation.SampleLoaderResolver;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@TestTransaction
class InteractionsSampleLoaderTests {
	private static final Instant NOW = Instant.now();
	private static final UUID INTERACTION_ID = UUID.randomUUID();
	private static final Interaction INTERACTION = Interaction.builder()
			.interactionDate(NOW)
			.interactionId(INTERACTION_ID)
			.applicationName("app")
			.interfaceName("interface")
			.methodName("method")
			.systemMessage("system message")
			.userMessage("user message")
			.result("result")
			.build();

	@Inject
	InteractionsSampleLoader loader;

	@Inject
	InteractionRepository interactionRepository;

	@Test
	void supportsWorksWhenInvalidSourceFormat() {
		assertThat(this.loader.supports("not-a-source"))
			.isFalse();
	}

	@Test
	void supportsWorksWhenNoSourceFound() {
		assertThat(this.loader.supports(Source.asSourceString(INTERACTION)))
			.isFalse();
	}

	@Test
	void supportsWorks() {
		loadSampleData();

		assertThat(this.loader.supports(Source.asSourceString(INTERACTION)))
			.isTrue();
	}

	@Test
	void loaderLoadsWhenSourceExists() {
		loadSampleData();

		var samples = SampleLoaderResolver.load(Source.asSourceString(INTERACTION), String.class);

		assertThat(samples)
			.isNotNull();
	}

	private void assertRepoSize(int expected) {
		assertThat(this.interactionRepository.count()).isEqualTo(expected);
	}

	private void loadSampleData() {
		this.interactionRepository.streamAll()
			.map(Interaction::getInteractionId)
			.forEach(this.interactionRepository::deleteById);

		assertRepoSize(0);
		this.interactionRepository.persist(INTERACTION);
		assertRepoSize(1);
	}
}