package ai.scoring.drift;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.quarkus.logging.Log;

import dev.langchain4j.guardrail.GuardrailResult.Failure;
import dev.langchain4j.guardrail.OutputGuardrailException;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import io.quarkiverse.langchain4j.chatscopes.ChatRouteContext;
import io.quarkiverse.langchain4j.chatscopes.ChatRouteExceptionHandler;

public class DriftDetectionChatRouteExceptionHandler {
	@ChatRouteExceptionHandler
	public static void handleDriftException(OutputGuardrailException ex, ChatRouteContext ctx) {
		// result() is null when the guardrail itself threw (e.g. a Langfuse lookup failed)
		Optional.ofNullable(ex.result())
			.map(OutputGuardrailResult::failures)
			.stream()
			.flatMap(List::stream)
			.filter(Objects::nonNull)
			.map(Failure::cause)
			.filter(DriftDetectionException.class::isInstance)
			.map(DriftDetectionException.class::cast)
			.findAny()
			.ifPresent(driftDetectionException -> {
				Log.infof("Handling drift detection exception: %s", driftDetectionException.getMessage());
				ctx.response().error("DRIFT DETECTED!!!\n\n%s".formatted(driftDetectionException.getMessage()));
			});
	}
}
