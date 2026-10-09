package org.parasol.intake.agent.extraction;

import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.parasol.claim.model.ClaimCategory;

import io.quarkus.qute.TemplateExtension;

/**
 * Values the extraction agents' prompts read as {@code {extraction:…}}, so the prompts stay in step with the enums.
 * <p>
 * The binding lives here, with the prompts that use it, rather than on the domain enums: each template formats an
 * enum its own way (these prompts need the constant names the model must answer with; an email would want the
 * labels).
 */
@TemplateExtension(namespace = "extraction")
final class ExtractionPromptExtensions {
	private ExtractionPromptExtensions() {
	}

	/**
	 * Lists every claim category the model may answer with.
	 *
	 * @return the {@link ClaimCategory} constant names in declaration order, joined with {@code ", "}
	 */
	static String claimCategories() {
		return Stream.of(ClaimCategory.values())
			.map(ClaimCategory::name)
			.collect(Collectors.joining(", "));
	}

	/**
	 * Names the category for an incident that's described but fits no other one.
	 *
	 * @return the {@link ClaimCategory#OTHER} constant name
	 */
	static String otherCategory() {
		return ClaimCategory.OTHER.name();
	}
}
