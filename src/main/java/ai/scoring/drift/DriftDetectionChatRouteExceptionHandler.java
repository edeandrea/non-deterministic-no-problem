package ai.scoring.drift;

import java.util.Objects;

import io.quarkus.logging.Log;

import dev.langchain4j.guardrail.GuardrailResult.Failure;
import dev.langchain4j.guardrail.OutputGuardrailException;
import io.quarkiverse.langchain4j.chatscopes.ChatRouteContext;
import io.quarkiverse.langchain4j.chatscopes.ChatRouteExceptionHandler;

public class DriftDetectionChatRouteExceptionHandler {
	@ChatRouteExceptionHandler
	public static void handleDriftException(OutputGuardrailException ex, ChatRouteContext ctx) {
		ex.result()
			.failures()
			.stream()
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
