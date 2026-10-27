package ai.scoring.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.Utf8Entry;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;

/**
 * The layering rule of the conversation core: the core never references an adapter (a sub-package) or a framework, and
 * adapters never reference each other. Reads every compiled class's constant pool (class references, descriptors and
 * signatures), so it also catches fully qualified uses an import check would miss.
 */
class ConversationLayeringTests {
	private static final String CORE = "ai/scoring/conversation";
	private static final Pattern ADAPTER_REFERENCE = Pattern.compile("ai/scoring/conversation/([a-z0-9_]+)/");
	private static final List<String> FORBIDDEN_IN_CORE = List.of(
		"io/quarkus/",
		"io/quarkiverse/",
		"dev/langchain4j/",
		"io/serverlessworkflow/",
		"io/vertx/",
		"org/parasol/"
	);

	@Test
	void theCoreReferencesNoAdapterAndNoFramework() {
		var core = referencesByClass().entrySet()
			.stream()
			.filter(e -> packageOf(e.getKey()).equals(CORE))
			.collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

		assertThat(core)
			.isNotEmpty()
			.allSatisfy((className, references) -> assertThat(references)
				.as("references of %s", className)
				.noneMatch(r -> ADAPTER_REFERENCE.matcher(r).find())
				.noneMatch(r -> FORBIDDEN_IN_CORE.stream().anyMatch(r::contains))
				.noneMatch(r -> r.contains("ai/scoring/") && !r.contains(CORE + "/")));
	}

	@Test
	void adaptersNeverReferenceEachOther() {
		var adapters = referencesByClass().entrySet()
			.stream()
			.filter(e -> !packageOf(e.getKey()).equals(CORE))
			.collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

		assertThat(adapters)
			.isNotEmpty()
			.allSatisfy((className, references) -> assertThat(adaptersReferencedBy(references))
				.as("adapters referenced by %s", className)
				.allMatch(adapter -> packageOf(className).equals(CORE + "/" + adapter)));
	}

	private static Set<String> adaptersReferencedBy(Set<String> references) {
		return references.stream()
			.flatMap(r -> ADAPTER_REFERENCE.matcher(r).results())
			.map(m -> m.group(1))
			.collect(Collectors.toUnmodifiableSet());
	}

	private static String packageOf(String className) {
		return className.substring(0, className.lastIndexOf('/'));
	}

	private static Map<String, Set<String>> referencesByClass() {
		try (var files = Files.walk(classesRoot().resolve(CORE))) {
			return files
				.filter(file -> file.toString().endsWith(".class"))
				.map(ConversationLayeringTests::parse)
				.collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static Map.Entry<String, Set<String>> parse(Path classFile) {
		try {
			var model = ClassFile.of().parse(classFile);
			var references = StreamSupport.stream(model.constantPool().spliterator(), false)
				.filter(Utf8Entry.class::isInstance)
				.map(Utf8Entry.class::cast)
				.map(Utf8Entry::stringValue)
				.collect(Collectors.toUnmodifiableSet());

			return Map.entry(model.thisClass().asInternalName(), references);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static Path classesRoot() {
		try {
			return Path.of(ConversationContext.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		}
		catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}
}
