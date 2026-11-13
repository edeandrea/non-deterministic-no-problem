package org.parasol.intake.agent.triage;

import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.parasol.intake.model.EmailType;

import io.quarkus.qute.TemplateExtension;

/**
 * Values the triage agents' prompts read as {@code {triage:…}}, so the prompts stay in step with the enums.
 * <p>
 * Like {@code ExtractionPromptExtensions}, the binding lives with the prompts that use it, not on the domain enum.
 */
@TemplateExtension(namespace = "triage")
final class TriagePromptExtensions {
	private TriagePromptExtensions() {
	}

	/**
	 * Lists every email type the classifier may answer with, each with its description.
	 *
	 * @return one {@code "  - NAME: description"} line per {@link EmailType}, in declaration order
	 */
	static String emailTypes() {
		return Stream.of(EmailType.values())
			.map(type -> "  - %s: %s".formatted(type.name(), type.description()))
			.collect(Collectors.joining("\n"));
	}
}
