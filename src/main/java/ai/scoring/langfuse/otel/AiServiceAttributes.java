package ai.scoring.langfuse.otel;

import io.opentelemetry.api.common.AttributeKey;

/**
 * The OpenTelemetry span attributes that link a LangChain4j AI service invocation to the Langfuse dataset its
 * generations are recorded into.
 * <p>
 * These are written onto spans by {@code AiServiceDatasetSpanProcessor} at runtime and read back off Langfuse
 * observation metadata by the session scorer. Keeping them in one place guarantees the writer and the reader
 * can't drift apart.
 */
public final class AiServiceAttributes {
	/** The span name prefix Quarkus LangChain4j uses for AI service method invocations. */
	public static final String AI_SERVICES_PREFIX = "langchain4j.aiservices.";

	/**
	 * The Langfuse dataset name: the AI service span name verbatim, i.e. {@code langchain4j.aiservices.<AiServiceClassName>.<methodName>}.
	 * <p>
	 * Deliberately the whole span name rather than a trimmed {@code Service.method} form:
	 * <ul>
	 *   <li>it namespaces auto-recorded datasets apart from hand-curated ones and groups them in the Langfuse UI</li>
	 *   <li>it can be pasted straight into the Langfuse trace search to find the spans that populated the dataset</li>
	 *   <li>the recording side needs no parsing at all - it just copies the ancestor's name</li>
	 * </ul>
	 */
	public static final AttributeKey<String> DATASET_NAME = AttributeKey.stringKey("langfuse.dataset.name");

	/** The simple class name of the AI service interface. Informational; not used to build the dataset name. */
	public static final AttributeKey<String> AI_SERVICE_CLASS = AttributeKey.stringKey("ai.service.class");

	/** The name of the AI service method that was invoked. Informational; not used to build the dataset name. */
	public static final AttributeKey<String> AI_SERVICE_METHOD = AttributeKey.stringKey("ai.service.method");

	private AiServiceAttributes() {
		// Constants holder
	}
}
