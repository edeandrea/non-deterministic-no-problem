package ai.scoring.evaluation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.CDI;

import ai.scoring.domain.interaction.Interaction;
import ai.scoring.domain.sample.Source;
import ai.scoring.repository.InteractionRepository;
import io.quarkiverse.langchain4j.testing.evaluation.EvaluationSample;
import io.quarkiverse.langchain4j.testing.evaluation.Parameters;
import io.quarkiverse.langchain4j.testing.evaluation.SampleLoadException;
import io.quarkiverse.langchain4j.testing.evaluation.SampleLoader;
import io.quarkiverse.langchain4j.testing.evaluation.Samples;

import io.quarkus.arc.Unremovable;

@ApplicationScoped
@Unremovable
public class InteractionsSampleLoader implements SampleLoader<String> {
//	private final InteractionRepository interactionRepository;
//
//	public InteractionsSampleLoader(InteractionRepository interactionRepository) {
//		this.interactionRepository = interactionRepository;
//	}

	@Override
	public boolean supports(String source) {
		// source should be in the format <APPLICATION_NAME>::<INTERFACE_NAME>::<METHOD_NAME>
		return Source.from(source)
		             .map(getInteractionRepository()::containsSource)
		             .orElse(false);
	}

	@Override
	public Samples<String> load(String source, Class<String> outputType) throws SampleLoadException {
		return new Samples(
			Source.from(source)
			      .map(getInteractionRepository()::findAllBySource)
						.orElseThrow(() -> new EvaluationException("No samples found for source: " + source + ""))
			      .stream()
			      .map(this::buildSample)
			      .toList()
		);
	}

	private EvaluationSample<String> buildSample(Interaction interaction) {
		return EvaluationSample.<String>builder()
		                       .withName(interaction.getInteractionId().toString())
		                       .withParameters(new Parameters()
			                       .add("systemMessage", interaction.getSystemMessage())
			                       .add("userMessage", interaction.getUserMessage()))
		                       .withExpectedOutput(interaction.getResult())
			                     .build();
	}

	// Helper to get CDI instance when created via ServiceLoader
  private InteractionRepository getInteractionRepository() {
//		return this.interactionRepository;
    return CDI.current().select(InteractionRepository.class).get();
  }
}
